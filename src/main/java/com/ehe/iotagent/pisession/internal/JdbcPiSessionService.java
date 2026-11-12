package com.ehe.iotagent.pisession.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.pisession.PiSessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Append-only MySQL journal for Pi's native codec; no local files or executable operations. */
public final class JdbcPiSessionService implements PiSessionService {
    private static final long LEASE_MS = 600_000;
    private static final Set<String> WRITES = Set.of("write", "append", "mkdir", "rename", "remove");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ContextResolver resolver;
    private final Clock clock;
    private final String serverId = UUID.randomUUID().toString();

    public JdbcPiSessionService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            ContextResolver resolver, Clock clock) {
        this.jdbc=Objects.requireNonNull(jdbc); this.transactions=Objects.requireNonNull(transactions);
        this.mapper=Objects.requireNonNull(mapper); this.resolver=Objects.requireNonNull(resolver);
        this.clock=Objects.requireNonNull(clock);
        new com.ehe.iotagent.schema.SchemaMigrations(jdbc.getDataSource()).ensurePiSessions();
    }

    @Override public Map<String,Object> execute(Map<String,Object> request) {
        if(request==null || number(request,"protocolVersion")!=1) throw failure("INVALID_PI_SESSION_REQUEST","会话协议版本无效",400);
        for(String key:List.of("runId","conversationId","turnId","toolContextId"))text(request,key,128);
        Context context=resolver.resolve(request);
        if(context==null || context.tenant()==null || context.owner()==null || context.source()==null
                || context.conversationId()==null || context.runId()==null || !context.runId().equals(text(request,"runId",128)))
            throw failure("PI_SESSION_CONTEXT_UNAVAILABLE","会话运行身份不可用",409);
        String scope=hash(json(List.of(context.tenant(),context.owner(),context.source(),context.conversationId())));
        String operation=text(request,"operation",32), writer=text(request,"writerId",64);
        return Objects.requireNonNull(transactions.execute(status -> {
            if("claim".equals(operation)) {
                jdbc.update("INSERT IGNORE INTO pi_session_scopes(scope_key,tenant_code,owner_key,source,conversation_id) VALUES(?,?,?,?,?)",scope,context.tenant(),context.owner(),context.source(),context.conversationId());
            }
            Scope row=jdbc.query("SELECT writer_id,run_id,server_id,binding_json,epoch,version,lease_until FROM pi_session_scopes WHERE scope_key=? FOR UPDATE",
                    (rs,index)->new Scope(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getLong(6),rs.getLong(7)),scope).stream().findFirst().orElseThrow(()->failure("PI_SESSION_WRITER_LOST","会话写入资格已失效",409));
            long now=clock.millis();
            if("claim".equals(operation)) {
                boolean same=writer.equals(row.writer)&&context.runId().equals(row.run);
                if(!same && row.writer!=null && row.until>now && !(serverId.equals(row.server)&&!active(row.binding)))
                    throw failure("PI_SESSION_BUSY","此会话已有正在处理的请求，请稍后重试",409);
                long epoch=same && row.until>now ? row.epoch : row.epoch+1;
                Map<String,Object> binding=new LinkedHashMap<>();
                for(String key:List.of("runId","conversationId","turnId","toolContextId")) binding.put(key,text(request,key,256));
                jdbc.update("UPDATE pi_session_scopes SET writer_id=?,run_id=?,server_id=?,binding_json=?,epoch=?,lease_until=?,update_time=CURRENT_TIMESTAMP WHERE scope_key=?",writer,context.runId(),serverId,json(binding),epoch,now+LEASE_MS,scope);
                ensureDirectory(scope,"/sessions",now);
                return response(epoch,row.version,null);
            }
            if(!writer.equals(row.writer)||!context.runId().equals(row.run)||number(request,"epoch")!=row.epoch||row.until<=now)
                throw failure("PI_SESSION_WRITER_LOST","会话写入资格已失效，请重新发起请求",409);
            if("check".equals(operation))return response(row.epoch,row.version,null);
            if("release".equals(operation)) {
                jdbc.update("UPDATE pi_session_scopes SET writer_id=NULL,run_id=NULL,binding_json=NULL,lease_until=0,update_time=CURRENT_TIMESTAMP WHERE scope_key=?",scope);
                return response(row.epoch,row.version,null);
            }
            if("commit-status".equals(operation)) {
                String commit=text(request,"commitId",64);
                String saved=jdbc.query("SELECT response_json FROM pi_session_commits WHERE scope_key=? AND commit_id=?",(rs,index)->rs.getString(1),scope,commit).stream().findFirst().orElse(null);
                return saved==null?response(row.epoch,row.version,null):object(saved);
            }
            String commit=null, digest=null;
            if(WRITES.contains(operation)) {
                commit=text(request,"commitId",64);
                Map<String,Object> payload=new LinkedHashMap<>(request);
                for(String key:List.of("protocolVersion","writerId","runId","conversationId","turnId","toolContextId","epoch","commitId"))payload.remove(key);
                digest=hash(json(payload));
                List<Map<String,Object>> existing=jdbc.query("SELECT request_hash,response_json FROM pi_session_commits WHERE scope_key=? AND commit_id=?",(rs,index)->Map.of("hash",rs.getString(1),"response",rs.getString(2)),scope,commit);
                if(!existing.isEmpty()) {
                    if(!digest.equals(existing.get(0).get("hash")))throw failure("PI_SESSION_COMMIT_CONFLICT","会话提交标识与内容冲突",409);
                    return object((String)existing.get(0).get("response"));
                }
                if(number(request,"expectedVersion")!=row.version)throw failure("PI_SESSION_VERSION_CONFLICT","会话已更新，请重新读取",409);
            }
            String path=path(request,"key");
            Object value=switch(operation) {
                case "exists" -> document(scope,path)!=null;
                case "info" -> info(requiredDocument(scope,path));
                case "list" -> children(scope,path);
                case "read" -> read(scope,path,0);
                case "lines" -> readLines(scope,path,request.get("maxLines"));
                case "write", "append" -> { write(scope,path,string(request.get("content")),"append".equals(operation),now); yield null; }
                case "mkdir" -> { makeDirectories(scope,path,!Boolean.FALSE.equals(request.get("recursive")),now); yield null; }
                case "rename" -> { rename(scope,path,path(request,"targetKey"),now); yield null; }
                case "remove" -> { remove(scope,path,Boolean.TRUE.equals(request.get("recursive")),Boolean.TRUE.equals(request.get("force"))); yield null; }
                default -> throw failure("INVALID_PI_SESSION_REQUEST","会话操作无效",400);
            };
            long version=row.version;
            if(commit!=null) {
                version++;
                jdbc.update("UPDATE pi_session_scopes SET version=?,update_time=CURRENT_TIMESTAMP WHERE scope_key=?",version,scope);
                Map<String,Object> result=response(row.epoch,version,value);
                jdbc.update("INSERT INTO pi_session_commits(scope_key,commit_id,request_hash,response_json) VALUES(?,?,?,?)",scope,commit,digest,json(result));
                return result;
            }
            return response(row.epoch,version,value);
        }));
    }

    @Override public Map<String,Object> health() {
        Integer reachable=jdbc.queryForObject("SELECT 1",Integer.class);
        Long scopes=jdbc.queryForObject("SELECT COUNT(*) FROM pi_session_scopes",Long.class);
        Long documents=jdbc.queryForObject("SELECT COUNT(*) FROM pi_session_documents WHERE kind='file'",Long.class);
        Long invalid=jdbc.queryForObject("SELECT COUNT(*) FROM pi_session_documents d WHERE d.kind='file' AND (d.next_chunk<>(SELECT COUNT(*) FROM pi_session_chunks c WHERE c.scope_key=d.scope_key AND c.document_key=d.document_key) OR EXISTS(SELECT 1 FROM pi_session_chunks c WHERE c.scope_key=d.scope_key AND c.document_key=d.document_key AND (c.chunk_no<0 OR c.chunk_no>=d.next_chunk)))",Long.class);
        return Map.of("status",Objects.equals(reachable,1)&&Objects.equals(invalid,0L)?"ok":"unavailable","backend","mysql","scopes",scopes,"documents",documents,"invalidJournals",invalid);
    }

    private boolean active(String binding) {
        if(binding==null)return false;
        try{return resolver.resolve(object(binding))!=null;}catch(BusinessException unavailable){return false;}
    }
    private void write(String scope,String path,String content,boolean append,long now) {
        Document doc=document(scope,path);
        if(doc!=null&&doc.directory())throw file("IS_DIRECTORY","不能向会话目录追加记录");
        makeDirectories(scope,parent(path),true,now);
        String key=hash(path);
        if(doc==null) {
            jdbc.update("INSERT INTO pi_session_documents(scope_key,document_key,parent_key,path_json,kind,modified_at) VALUES(?,?,?,?, 'file',?)",scope,key,hash(parent(path)),json(path),now);
            doc=new Document(path,false,0,0,now);
        } else if(!append) {
            jdbc.update("DELETE FROM pi_session_chunks WHERE scope_key=? AND document_key=?",scope,key);
            doc=new Document(path,false,0,0,now);
        }
        jdbc.update("INSERT INTO pi_session_chunks(scope_key,document_key,chunk_no,content_json) VALUES(?,?,?,?)",scope,key,doc.chunks,json(content));
        jdbc.update("UPDATE pi_session_documents SET next_chunk=?,content_size=?,modified_at=?,update_time=CURRENT_TIMESTAMP WHERE scope_key=? AND document_key=?",doc.chunks+1,doc.size+utf8Length(content),now,scope,key);
    }
    private void makeDirectories(String scope,String path,boolean recursive,long now) {
        if("/sessions".equals(path)){ensureDirectory(scope,path,now);return;}
        Document existing=document(scope,path);
        if(existing!=null){if(!existing.directory())throw file("NOT_DIRECTORY","会话目录标识被占用");return;}
        String parent=parent(path);
        if(recursive)makeDirectories(scope,parent,true,now);
        else if(document(scope,parent)==null)throw file("NOT_FOUND","会话目录不存在");
        ensureDirectory(scope,path,now);
    }
    private void ensureDirectory(String scope,String path,long now) {
        jdbc.update("INSERT IGNORE INTO pi_session_documents(scope_key,document_key,parent_key,path_json,kind,modified_at) VALUES(?,?,?,?, 'directory',?)",scope,hash(path),"/sessions".equals(path)?null:hash(parent(path)),json(path),now);
    }
    private void rename(String scope,String source,String target,long now) {
        Document doc=requiredDocument(scope,source);
        if(doc.directory())throw file("NOT_SUPPORTED","会话目录不支持重命名");
        if(source.equals(target))return;
        makeDirectories(scope,parent(target),true,now);
        Document old=document(scope,target);
        if(old!=null&&old.directory())throw file("IS_DIRECTORY","会话目标是目录");
        remove(scope,target,false,true);
        jdbc.update("UPDATE pi_session_documents SET document_key=?,parent_key=?,path_json=?,modified_at=?,update_time=CURRENT_TIMESTAMP WHERE scope_key=? AND document_key=?",hash(target),hash(parent(target)),json(target),now,scope,hash(source));
        jdbc.update("UPDATE pi_session_chunks SET document_key=?,update_time=CURRENT_TIMESTAMP WHERE scope_key=? AND document_key=?",hash(target),scope,hash(source));
    }
    private void remove(String scope,String path,boolean recursive,boolean force) {
        Document doc=document(scope,path);
        if(doc==null){if(!force)throw file("NOT_FOUND","会话记录不存在");return;}
        List<Document> nested=doc.directory()?jdbc.query("SELECT path_json,kind,next_chunk,content_size,modified_at FROM pi_session_documents WHERE scope_key=?",(rs,index)->new Document(stringValue(rs.getString(1)),"directory".equals(rs.getString(2)),rs.getLong(3),rs.getLong(4),rs.getLong(5)),scope).stream().filter(x->x.path.startsWith(path+"/")).toList():List.of();
        if(!recursive&&!nested.isEmpty())throw file("INVALID","会话目录非空");
        for(Document child:nested)deleteDocument(scope,child.path);
        deleteDocument(scope,path);
    }
    private void deleteDocument(String scope,String path) {
        jdbc.update("DELETE FROM pi_session_chunks WHERE scope_key=? AND document_key=?",scope,hash(path));
        jdbc.update("DELETE FROM pi_session_documents WHERE scope_key=? AND document_key=?",scope,hash(path));
    }
    private List<Map<String,Object>> children(String scope,String path) {
        if(!requiredDocument(scope,path).directory())throw file("NOT_DIRECTORY","会话对象不是目录");
        return jdbc.query("SELECT path_json,kind,next_chunk,content_size,modified_at FROM pi_session_documents WHERE scope_key=? AND parent_key=? ORDER BY document_key",(rs,index)->info(new Document(stringValue(rs.getString(1)),"directory".equals(rs.getString(2)),rs.getLong(3),rs.getLong(4),rs.getLong(5))),scope,hash(path));
    }
    private String read(String scope,String path,int maxLines) {
        Document doc=requiredDocument(scope,path);
        if(doc.directory())throw file("IS_DIRECTORY","会话对象是目录");
        StringBuilder result=new StringBuilder();
        long offset=0; int lines=0;
        while(offset<doc.chunks) {
            List<Chunk> page=jdbc.query("SELECT chunk_no,content_json FROM pi_session_chunks WHERE scope_key=? AND document_key=? AND chunk_no>=? ORDER BY chunk_no LIMIT 64",(rs,index)->new Chunk(rs.getLong(1),stringValue(rs.getString(2))),scope,hash(path),offset);
            if(page.isEmpty())throw failure("PI_SESSION_CORRUPT","会话记录不完整",503);
            for(Chunk stored:page) {
                if(stored.index!=offset)throw failure("PI_SESSION_CORRUPT","会话记录顺序不完整",503);
                String chunk=stored.content;
                result.append(chunk);offset++;
                if(maxLines>0){for(int i=0;i<chunk.length();i++)if(chunk.charAt(i)==10)lines++;if(lines>=maxLines)return result.toString();}
            }
        }
        return result.toString();
    }
    private List<String> readLines(String scope,String path,Object requested) {
        int limit=requested instanceof Number n?n.intValue():0;
        if(limit<0)throw file("INVALID","会话读取数量无效");
        String content=read(scope,path,limit);
        if(content.isEmpty())return List.of();
        String[] lines=content.split("\n",-1);
        int size=lines.length-(content.endsWith("\n")?1:0);
        if(limit>0)size=Math.min(size,limit);
        List<String> result=new ArrayList<>(size);
        for(int i=0;i<size;i++)result.add(lines[i].endsWith("\r")?lines[i].substring(0,lines[i].length()-1):lines[i]);
        return result;
    }
    private Document requiredDocument(String scope,String path) {Document d=document(scope,path);if(d==null)throw file("NOT_FOUND","会话记录不存在");return d;}
    private Document document(String scope,String path) {
        return jdbc.query("SELECT path_json,kind,next_chunk,content_size,modified_at FROM pi_session_documents WHERE scope_key=? AND document_key=?",(rs,index)->new Document(stringValue(rs.getString(1)),"directory".equals(rs.getString(2)),rs.getLong(3),rs.getLong(4),rs.getLong(5)),scope,hash(path)).stream().findFirst().orElse(null);
    }
    private static Map<String,Object> info(Document d) {return Map.of("path",d.path,"name",d.path.substring(d.path.lastIndexOf('/')+1),"kind",d.directory?"directory":"file","size",d.size,"mtimeMs",d.modified);}
    private static Map<String,Object> response(long epoch,long version,Object value) {Map<String,Object> m=new LinkedHashMap<>();m.put("protocolVersion",1);m.put("epoch",epoch);m.put("version",version);m.put("value",value);return m;}
    private static String path(Map<String,Object> r,String key) {String p=text(r,key,65536);if(!p.equals("/sessions")&&!p.startsWith("/sessions/"))throw file("PERMISSION_DENIED","会话对象范围无效");for(String part:p.split("/",-1))if(part.equals("..")||part.equals(".")||part.indexOf('\0')>=0)throw file("INVALID","会话对象标识无效");return p;}
    private static String parent(String path) {int at=path.lastIndexOf('/');return at<=0?"/sessions":path.substring(0,at);}
    private static long number(Map<String,Object> r,String key) {Object v=r.get(key);if(!(v instanceof Number n)||n.doubleValue()!=n.longValue())throw failure("INVALID_PI_SESSION_REQUEST","会话数值无效",400);return n.longValue();}
    private static String text(Map<String,Object> r,String key,int max) {Object v=r.get(key);if(!(v instanceof String s)||s.isEmpty()||s.length()>max)throw failure("INVALID_PI_SESSION_REQUEST","会话参数无效",400);return s;}
    private static String string(Object v) {if(!(v instanceof String s))throw failure("INVALID_PI_SESSION_REQUEST","会话内容无效",400);return s;}
    private String json(Object v) {try{return mapper.writeValueAsString(v);}catch(JsonProcessingException e){throw failure("INVALID_PI_SESSION_REQUEST","会话内容不可序列化",400);}}
    private Map<String,Object> object(String v) {try{return mapper.readValue(v,mapper.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,Object.class));}catch(JsonProcessingException e){throw failure("PI_SESSION_CORRUPT","会话记录不可读取",503);}}
    private String stringValue(String v) {try{return mapper.readValue(v,String.class);}catch(JsonProcessingException e){throw failure("PI_SESSION_CORRUPT","会话记录不可读取",503);}}
    private static String hash(String v) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static long utf8Length(String s){long n=0;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c<128)n++;else if(c<2048)n+=2;else if(Character.isHighSurrogate(c)&&i+1<s.length()&&Character.isLowSurrogate(s.charAt(i+1))){n+=4;i++;}else n+=3;}return n;}
    private static BusinessException file(String code,String message){return failure("PI_FILE_"+code,message,"NOT_FOUND".equals(code)?404:400);}
    private static BusinessException failure(String code,String message,int status){return new BusinessException(code,message,status);}
    private record Scope(String writer,String run,String server,String binding,long epoch,long version,long until) {}
    private record Document(String path,boolean directory,long chunks,long size,long modified) {}
    private record Chunk(long index,String content) {}
}
