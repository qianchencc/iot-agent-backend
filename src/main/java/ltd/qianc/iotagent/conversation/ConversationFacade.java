package ltd.qianc.iotagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

public final class ConversationFacade {
 // Node/Pi bridge event contract includes message.delta; unknown bridge event types are forwarded without reinterpretation.
 private static final Set<String> TERMINALS=Set.of("run.completed","run.awaiting_confirmation","run.failed","run.aborted");
 private final ObjectMapper mapper; private final ConversationStore store; private final ConversationEventStore events; private final AgentEngineBridge bridge; private final Executor executor; private final ScheduledExecutorService scheduler; private final long heartbeatMs; private final Map<String,Run> active=new ConcurrentHashMap<>(); private final Map<String,Run> toolContexts=new ConcurrentHashMap<>(); private final Map<String,Run> toolContextsById=new ConcurrentHashMap<>();
 public ConversationFacade(ObjectMapper m,ConversationStore s,ConversationEventStore e,AgentEngineBridge b,Executor x){this(m,s,e,b,x,null,0);}
 public ConversationFacade(ObjectMapper m,ConversationStore s,ConversationEventStore e,AgentEngineBridge b,Executor x,ScheduledExecutorService scheduler,long heartbeatMs){mapper=m;store=s;events=e;bridge=b;executor=x;this.scheduler=scheduler;this.heartbeatMs=heartbeatMs;}
 public Map<String,Object> create(PrincipalInfo p,String source){String s=source==null?"office":source; validSource(s); ConversationRecord c=store.create(p.tenantCode(),owner(p),s,Instant.now());return Map.of("id",c.id(),"source",c.source(),"createdAt",c.createdAt().toString());}
 public Map<String,Object> current(PrincipalInfo p,String source){String s=source==null?"office":source;validSource(s);Optional<ConversationRecord> o=store.current(p.tenantCode(),owner(p),s,Instant.now().minus(Duration.ofHours(6)));if(o.isEmpty()){Map<String,Object> x=new HashMap<>();x.put("conversation",null);return x;}ConversationRecord c=o.get();List<Map<String,Object>> ts=new ArrayList<>();for(ConversationTurn t:store.turns(c.id()))ts.add(turn(c.id(),t));return Map.of("conversation",Map.of("id",c.id(),"source",c.source(),"turns",ts,"running",active.containsKey(c.id()),"phase",active.containsKey(c.id())?"running":"idle"));}
 public Map<String,Object> clear(PrincipalInfo p,String id){return clear(p,id,null);}
 public Map<String,Object> clear(PrincipalInfo p,String id,String source){verify(p,id,source);if(active.containsKey(id))throw new BusinessException("CONVERSATION_BUSY","请等待当前请求结束后清除",409);store.clear(id);return Map.of("cleared",true);}
 public Map<String,Object> discard(PrincipalInfo p,String id,String tid){return discard(p,id,tid,null);}
 public Map<String,Object> discard(PrincipalInfo p,String id,String tid,String source){verify(p,id,source);if(active.containsKey(id))throw new BusinessException("CONVERSATION_BUSY","当前会话正在处理请求，请等待完成或停止",409);ConversationTurn t=store.latestTurn(id).orElseThrow(()->new BusinessException("TURN_NOT_FOUND","只能从最后一条请求继续",409));if(!t.id().equals(tid))throw new BusinessException("TURN_NOT_LATEST","只能从最后一条请求继续",409);store.discardTurn(id,tid);return Map.of("discarded",true);}
 public ResponseBodyEmitter message(PrincipalInfo p,String id,String content,String idem){return message(p,id,content,idem,null,null,null);}
 public ResponseBodyEmitter message(PrincipalInfo p,String id,String content,String idem,String source){return message(p,id,content,idem,source,null,null);}
 public ResponseBodyEmitter message(PrincipalInfo p,String id,String content,String idem,String source,String focusRegionId,Map<String,Object> reference){
  ConversationRecord conversation=verify(p,id,source);
  if(content==null||content.isBlank())throw new BusinessException("INVALID_MESSAGE","Message content is required");
  if(active.containsKey(id))throw new BusinessException("CONVERSATION_BUSY","当前会话正在处理请求，请等待完成或停止",409);
  String key=idem==null||idem.isBlank()?UUID.randomUUID().toString():idem;
  Optional<ConversationTurn> old=store.findTurnByIdempotencyKey(id,key);
  ResponseBodyEmitter out=new ResponseBodyEmitter(0L);
  if(old.isPresent()){
   for(ConversationEventFact f:events.read(id).stream().filter(x->x.turnId().equals(old.get().id())).sorted(Comparator.comparingLong(ConversationEventFact::sequence)).toList())try{out.send(f.payloadJson()+"\n");}catch(Exception ignored){}
   out.complete();
   return out;
  }
  ConversationTurn turn=store.startTurn(id,content,Instant.now(),key);
  String tid=turn.id(),rid=UUID.randomUUID().toString(),tcid=UUID.randomUUID().toString();
  Run r=new Run(id,tid,rid,tcid,p,conversation.source(),out);
  toolContextsById.put(tcid,r);
  if(active.putIfAbsent(id,r)!=null){toolContextsById.remove(tcid,r);store.discardTurn(id,tid);throw new BusinessException("CONVERSATION_BUSY","当前会话正在处理请求，请等待完成或停止",409);}
  try{emit(r,"run.started",Map.of("createdAt",Instant.now().toString()));scheduleHeartbeat(r);}
  catch(RuntimeException error){active.remove(id,r);toolContextsById.remove(tcid,r);try{store.discardTurn(id,tid);}catch(RuntimeException ignored){}throw error;}
  try {
   executor.execute(()->{
   try{
    bridge.start(new AgentRunRequest(rid,id,tid,conversation.source(),content,p.tenantCode(),focusRegionId,reference,tcid),e->{if(!r.terminal.get()&&!"run.started".equals(e.type()))emit(r,e.type(),e.fields());}).whenComplete((v,x)->{
     if(x!=null&&!r.terminal.get()){Throwable cause=x instanceof CompletionException&&x.getCause()!=null?x.getCause():x;String message=cause instanceof BusinessException be&&"AGENT_ENGINE_TIMEOUT".equals(be.code())?"运行超时":"Agent Engine 暂不可用";finish(r,"run.failed",Map.of("message",message));}
     else if(!r.terminal.get())finish(r,"run.failed",Map.of("message","运行未返回终态"));
    });
   }catch(Exception e){finish(r,"run.failed",Map.of("message","Agent Engine 暂不可用"));}
  });
  } catch (RejectedExecutionException e) {
   finish(r,"run.failed",Map.of("message","当前运行数量已达上限，请稍后重试"));
  }
  return out;
 }
 public Map<String,Object> cancel(PrincipalInfo p,String id){return cancel(p,id,null);}
 public Map<String,Object> cancel(PrincipalInfo p,String id,String source){verify(p,id,source);Run r=active.get(id);if(r==null)throw new BusinessException("NO_ACTIVE_RUN","当前没有正在处理的请求",409);finish(r,"run.aborted",Map.of("message","已停止"));bridge.cancel(r.runId);return Map.of("cancelled",true);}
 public PrincipalInfo toolPrincipal(String toolContextId,String conversationId,String turnId){
  Run r=toolContextsById.get(toolContextId);
  if(r==null)r=active.get(conversationId);
  if(r==null)throw new BusinessException("AGENT_TOOL_RUN_UNAVAILABLE","Agent 运行上下文不可用",409);
  return r.principal;
 }
 private void emit(Run r,String type,Map<String,Object> f){
  if("heartbeat".equals(type)){sendHeartbeat(r);return;}
  if(TERMINALS.contains(type)){finish(r,type,f);return;}
  synchronized(r){if(r.terminal.get())return;ObjectNode n=node(r,type,f);append(r,n);send(r,n);}
 }
 private void finish(Run r,String type,Map<String,Object> f){
  synchronized(r){
   if(!r.terminal.compareAndSet(false,true))return;
   try{ObjectNode n=node(r,type,f);append(r,n);send(r,n);}catch(RuntimeException ignored){}
   finally{if(r.heartbeat!=null)r.heartbeat.cancel(false);try{r.out.complete();}catch(Exception ignored){}active.remove(r.conversationId,r);toolContexts.put(r.conversationId,r);if(scheduler!=null){scheduler.schedule(()->toolContexts.remove(r.conversationId,r),60,TimeUnit.SECONDS);scheduler.schedule(()->toolContextsById.remove(r.toolContextId,r),60,TimeUnit.SECONDS);}}
  }
 }
 private void scheduleHeartbeat(Run r){
  if(scheduler!=null&&heartbeatMs>0)r.heartbeat=scheduler.scheduleAtFixedRate(()->sendHeartbeat(r),heartbeatMs,heartbeatMs,TimeUnit.MILLISECONDS);
 }
 private void sendHeartbeat(Run r){
  synchronized(r){if(!r.terminal.get()){ObjectNode n=mapper.createObjectNode();n.put("type","heartbeat");send(r,n);}}
 }
 private ObjectNode node(Run r,String type,Map<String,Object> f){
  ObjectNode n=mapper.createObjectNode();n.put("type",type);n.put("sequence",++r.sequence);n.put("conversationId",r.conversationId);n.put("turnId",r.turnId);n.put("timestamp",Instant.now().toString());
  f.forEach((k,v)->{if(!Set.of("type","sequence","conversationId","turnId","timestamp").contains(k))n.set(k,mapper.valueToTree(v));});
  return n;
 }
 private void append(Run r,ObjectNode n){events.append(new ConversationEventFact(r.conversationId,r.turnId,r.sequence,n.get("type").asText(),n.toString(),r.runId+":"+r.sequence));}
 private void send(Run r,ObjectNode n){try{if(!r.disconnected.get())r.out.send(n.toString()+"\n");}catch(Exception e){r.disconnected.set(true);}}
 private Map<String,Object> turn(String id,ConversationTurn t){List<Object> es=new ArrayList<>();for(ConversationEventFact f:events.read(id).stream().filter(x->x.turnId().equals(t.id())).sorted(Comparator.comparingLong(ConversationEventFact::sequence)).toList())try{es.add(mapper.readTree(f.payloadJson()));}catch(Exception ignored){}return Map.of("id",t.id(),"content",t.content(),"startedAt",t.startedAt().toString(),"events",es);}
 private ConversationRecord verify(PrincipalInfo p,String id,String source){
  ConversationRecord conversation=store.find(id,p.tenantCode(),owner(p)).orElseThrow(()->new BusinessException("CONVERSATION_UNAVAILABLE","会话已过期或不可用，请清除后重新开始",404));
  if(conversation.cleared()||conversation.createdAt().isBefore(Instant.now().minus(Duration.ofHours(6))))throw new BusinessException("CONVERSATION_UNAVAILABLE","会话已过期或不可用，请清除后重新开始",404);
  String effectiveSource=source==null?"office":source;validSource(effectiveSource);if(!effectiveSource.equals(conversation.source()))throw new BusinessException("CONVERSATION_SOURCE_MISMATCH","会话来源与当前请求不一致",404);
  return conversation;
}
 private static void validSource(String s){if(!s.equals("office")&&!s.equals("company"))throw new BusinessException("INVALID_AGENT_SOURCE","Agent 来源无效");}
 private static String owner(PrincipalInfo p){return p.ownerKey();}
 private static final class Run{
  final String conversationId,turnId,runId,toolContextId,source;final PrincipalInfo principal;final ResponseBodyEmitter out;final AtomicBoolean terminal=new AtomicBoolean();final AtomicBoolean disconnected=new AtomicBoolean();long sequence;ScheduledFuture<?> heartbeat;
  Run(String c,String t,String r,String tc,PrincipalInfo p,String s,ResponseBodyEmitter o){conversationId=c;turnId=t;runId=r;toolContextId=tc;principal=p;source=s;out=o;o.onError(x->disconnected.set(true));o.onCompletion(()->disconnected.set(true));}
 }
}
