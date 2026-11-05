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
import ltd.qianc.iotagent.audit.AuditEventFact;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

public final class ConversationFacade {
 // Business-owned Java projection converts private engine events before they reach this facade.
 private static final Set<String> TERMINALS=Set.of("run.completed","run.awaiting_confirmation","run.failed","run.aborted");
 private final ObjectMapper mapper; private final ConversationStore store; private final ConversationEventStore events; private final AgentEngineBridge bridge; private final Executor executor; private final ScheduledExecutorService scheduler; private final java.util.function.LongSupplier heartbeatMs; private final ConversationDrafts drafts; private final ConversationTaskCards taskCards; private final AuditEventStore audit; private final Map<String,Run> active=new ConcurrentHashMap<>(); private final Map<String,Run> toolContextsById=new ConcurrentHashMap<>();
 public ConversationFacade(ObjectMapper m,ConversationStore s,ConversationEventStore e,AgentEngineBridge b,Executor x,ConversationDrafts d,ConversationTaskCards taskCards){this(m,s,e,b,x,null,0,d,taskCards,null);}
 public ConversationFacade(ObjectMapper m,ConversationStore s,ConversationEventStore e,AgentEngineBridge b,Executor x,ScheduledExecutorService scheduler,long heartbeatMs,ConversationDrafts d,ConversationTaskCards taskCards){this(m,s,e,b,x,scheduler,heartbeatMs,d,taskCards,null);}
 public ConversationFacade(ObjectMapper m,ConversationStore s,ConversationEventStore e,AgentEngineBridge b,Executor x,ScheduledExecutorService scheduler,long heartbeatMs,ConversationDrafts d,ConversationTaskCards taskCards,AuditEventStore audit){this(m,s,e,b,x,scheduler,()->heartbeatMs,d,taskCards,audit);}
 public ConversationFacade(ObjectMapper m,ConversationStore s,ConversationEventStore e,AgentEngineBridge b,Executor x,ScheduledExecutorService scheduler,java.util.function.LongSupplier heartbeatMs,ConversationDrafts d,ConversationTaskCards taskCards,AuditEventStore audit){mapper=m;store=s;events=e;bridge=b;executor=x;this.scheduler=scheduler;this.heartbeatMs=heartbeatMs;drafts=Objects.requireNonNull(d);this.taskCards=Objects.requireNonNull(taskCards);this.audit=audit;}
 public Map<String,Object> create(PrincipalInfo p,String source){String s=source==null?"office":source; validSource(s); ConversationRecord c=store.create(p.tenantCode(),owner(p),s,Instant.now());return Map.of("id",c.id(),"source",c.source(),"createdAt",c.createdAt().toString());}
 public Map<String,Object> current(PrincipalInfo p,String source) {
  String s=source==null?"office":source;
  validSource(s);
  Optional<ConversationRecord> current=store.current(p.tenantCode(),owner(p),s,Instant.now().minus(Duration.ofHours(6)));
  if(current.isEmpty()) {
   Map<String,Object> result=new HashMap<>();
   result.put("conversation",null);
   return result;
  }
  ConversationRecord conversation=current.get();
  List<ConversationTurn> saved=store.turns(conversation.id());
  Map<String,List<ConversationEventFact>> byTurn=new HashMap<>();
  if(!saved.isEmpty()) for(ConversationEventFact event:events.read(conversation.id()))
   byTurn.computeIfAbsent(event.turnId(),ignored->new ArrayList<>()).add(event);
  Map<String,Map<String,Object>> authoritative=drafts.conversationDrafts(p,conversation.id(),s);
  List<Map<String,Object>> turns=new ArrayList<>(saved.size());
  for(ConversationTurn row:saved) turns.add(turn(row,byTurn.getOrDefault(row.id(),List.of()),authoritative,p,s));
  boolean running=active.containsKey(conversation.id());
  return Map.of("conversation",Map.of("id",conversation.id(),"source",conversation.source(),
   "turns",turns,"running",running,"phase",running?"running":"idle"));
 }
 public Map<String,Object> clear(PrincipalInfo p,String id){return clear(p,id,null);}
 public Map<String,Object> clear(PrincipalInfo p,String id,String source){verify(p,id,source);if(active.containsKey(id))throw new BusinessException("CONVERSATION_BUSY","请等待当前请求结束后清除",409);store.clear(id);return Map.of("cleared",true);}
 public Map<String,Object> discard(PrincipalInfo p,String id,String tid){return discard(p,id,tid,null);}
 public Map<String,Object> discard(PrincipalInfo p,String id,String tid,String source){verify(p,id,source);if(active.containsKey(id))throw new BusinessException("CONVERSATION_BUSY","当前会话正在处理请求，请等待完成或停止",409);ConversationTurn t=store.latestTurn(id).orElseThrow(()->new BusinessException("TURN_NOT_FOUND","只能从最后一条请求继续",409));if(!t.id().equals(tid))throw new BusinessException("TURN_NOT_LATEST","只能从最后一条请求继续",409);store.discardTurn(id,tid);return Map.of("discarded",true);}
 public ResponseBodyEmitter message(PrincipalInfo p,String id,String content,String idem){return message(p,id,content,idem,null,null,null);}
 public ResponseBodyEmitter message(PrincipalInfo p,String id,String content,String idem,String source){return message(p,id,content,idem,source,null,null);}
 public ResponseBodyEmitter message(PrincipalInfo p,String id,String content,String idem,String source,String focusRegionId,Map<String,Object> reference){
  long receivedAt=System.nanoTime();
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
  Run r=new Run(id,tid,rid,tcid,p,conversation.source(),focusRegionId,reference,out);
  r.receivedAt=receivedAt;
  toolContextsById.put(tcid,r);
  if(active.putIfAbsent(id,r)!=null){toolContextsById.remove(tcid,r);store.discardTurn(id,tid);throw new BusinessException("CONVERSATION_BUSY","当前会话正在处理请求，请等待完成或停止",409);}
  try{audit(r,"AGENT_REQUESTED",requested(content));}
  catch(RuntimeException failure){
   try{store.discardTurn(id,tid);}finally{active.remove(id,r);toolContextsById.remove(tcid,r);}
   throw failure;
  }
  AgentRunTiming.record(r.runId,"java.accept",receivedAt,"completed");
  launch(r,content);
  return out;
 }
 private void launch(Run r,String content){
  long queuedAt=System.nanoTime();
  try{
   scheduleHeartbeat(r);
   executor.execute(()->{
    AgentRunTiming.record(r.runId,"java.queue",queuedAt,"started");
    try{
     bridge.start(new AgentRunRequest(r.runId,r.conversationId,r.turnId,r.context.source(),content,
       r.context.principal().tenantCode(),r.context.focusRegionId(),r.context.reference(),r.toolContextId),
       e->{if(!r.terminal.get())emit(r,e.type(),e.fields());}).whenComplete((v,x)->{
      if(x!=null&&!r.terminal.get())fail(r,x);
      else if(!r.terminal.get())fail(r,new BusinessException("AGENT_ENGINE_INCOMPLETE","运行未返回终态",502));
     });
    }catch(Exception e){fail(r,e);}
   });
  }catch(RejectedExecutionException e){
   fail(r,new BusinessException("AGENT_CAPACITY_EXCEEDED","当前运行数量已达上限，请稍后重试",429));
  }catch(RuntimeException e){fail(r,e);}
 }
 /** System continuation reuses the original turn and does not grant confirmation authority. */
 public boolean continueTaskConfirmation(PrincipalInfo principal,String id,String tid,String taskId,
   String source,String code,String message){
  verify(principal,id,source);
  ConversationTurn turn=store.turns(id).stream().filter(t->t.id().equals(tid)).findFirst()
    .orElseThrow(()->new BusinessException("TURN_NOT_FOUND","原任务请求不存在",404));
  Run r;
  synchronized(events){
   List<ConversationEventFact> facts=events.read(id).stream().filter(f->f.turnId().equals(tid))
     .sorted(Comparator.comparingLong(ConversationEventFact::sequence)).toList();
   String focus=null;Map<String,Object> reference=null;
   boolean sameDecision=false;
   for(ConversationEventFact fact:facts){
    try{
     var body=mapper.readTree(fact.payloadJson());
     if("task.confirmation".equals(fact.type())&&taskId.equals(body.path("taskId").asText())){
      sameDecision="needs_decision".equals(body.path("confirmation").path("status").asText())
        &&code.equals(body.path("confirmation").path("code").asText());
     }
     if("run.started".equals(fact.type())){
      if(body.path("focusRegion").path("id").isTextual())focus=body.path("focusRegion").path("id").asText();
      if(body.path("reference").isObject())reference=mapper.convertValue(body.path("reference"),Map.class);
     }
    }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
     throw new BusinessException("CONVERSATION_EVENT_INVALID","会话记录不可读取",503);
    }
   }
   if(sameDecision)return false;
   r=new Run(id,tid,UUID.randomUUID().toString(),UUID.randomUUID().toString(),principal,source,
     focus,reference,new ResponseBodyEmitter(0L));
   r.continuation=true;
   r.confirmationTaskId=taskId;
   r.sequence=facts.stream().mapToLong(ConversationEventFact::sequence).max().orElse(0);
   if(active.putIfAbsent(id,r)!=null)throw new BusinessException("CONVERSATION_BUSY","当前会话正在处理请求",409);
   toolContextsById.put(r.toolContextId,r);
   try{
    ObjectNode decision=node(r,"task.confirmation",Map.of("taskId",taskId,"confirmation",
      Map.of("status","needs_decision","code",code,"message",message)));
    append(r,decision);
   }catch(RuntimeException failure){
    active.remove(id,r);toolContextsById.remove(r.toolContextId,r);throw failure;
   }
  }
  launch(r,turn.content());
  return true;
 }
 public Map<String,Object> observationReport(String contextId,String id,String tid,String rid,String reportId){
  toolContext(contextId,id,tid,rid);
  List<ConversationEventFact> facts=events.read(id);
  for(int index=facts.size()-1;index>=0;index--){
   ConversationEventFact fact=facts.get(index);
   if(!"observation.reported".equals(fact.type()))continue;
   try{
    for(var block:mapper.readTree(fact.payloadJson()).path("blocks")){
     var report=block.path("report");
     if(reportId.equals(report.path("reportId").asText()))return mapper.convertValue(report,Map.class);
    }
   }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
    throw new BusinessException("CONVERSATION_EVENT_INVALID","会话记录不可读取",503);
   }
  }
  throw new BusinessException("ENERGY_REPORT_NOT_FOUND","原比较报告不存在",404);
 }
 public boolean isContinuation(String contextId,String conversationId,String turnId,String runId){
  toolContext(contextId,conversationId,turnId,runId);
  return toolContextsById.get(contextId).continuation;
 }
 public void requireIdle(PrincipalInfo principal,String id,String source){
  verify(principal,id,source);
  if(active.containsKey(id))throw new BusinessException("CONVERSATION_BUSY","当前会话正在处理请求",409);
 }
 public void recordTaskConfirmation(PrincipalInfo principal,String id,String tid,String taskId,String source,
   String status,String message,String code){
  verify(principal,id,source);
  if(store.turns(id).stream().noneMatch(turn->tid.equals(turn.id())))
   throw new BusinessException("TURN_NOT_FOUND","原任务请求不存在",404);
  synchronized(events){
   long sequence=events.read(id).stream().filter(f->tid.equals(f.turnId()))
     .mapToLong(ConversationEventFact::sequence).max().orElse(0)+1;
   Map<String,Object> confirmation=new LinkedHashMap<>();
   confirmation.put("status",status);confirmation.put("message",message);
   if(code!=null)confirmation.put("code",code);
   ObjectNode body=mapper.valueToTree(Map.of("type","task.confirmation","conversationId",id,"turnId",tid,
     "sequence",sequence,"timestamp",Instant.now().toString(),"taskId",taskId,"confirmation",confirmation));
   events.append(new ConversationEventFact(id,tid,sequence,"task.confirmation",body.toString(),
     "confirmation:"+taskId+":"+sequence));
  }
 }
 public List<Map<String,Object>> taskConfirmationContext(String contextId,String id,String tid,String rid){
  AgentToolContext context=toolContext(contextId,id,tid,rid);
  Map<String,ConversationTurn> turns=new HashMap<>();
  for(ConversationTurn turn:store.turns(id))turns.put(turn.id(),turn);
  Map<String,Map<String,Object>> latest=new LinkedHashMap<>();
  List<ConversationEventFact> facts=new ArrayList<>(events.read(id));
  Collections.reverse(facts);
  for(ConversationEventFact fact:facts){
   if(!"task.confirmation".equals(fact.type()))continue;
   try{
    var body=mapper.readTree(fact.payloadJson());String taskId=body.path("taskId").asText();
    ConversationTurn original=turns.get(fact.turnId());
    if(taskId.isBlank()||latest.containsKey(taskId)||original==null)continue;
    Map<String,Object> state=taskCards.conversationProposalState(context.principal(),taskId,context.source());
    if(state==null)continue;
    Map<String,Object> item=new LinkedHashMap<>();
    item.put("taskId",taskId);item.put("originalGoal",original.content());
    item.put("proposalStatus",state.get("status"));
    item.put("plannedAt",state.getOrDefault("plannedAt",List.of()));
    item.put("confirmation",mapper.convertValue(body.path("confirmation"),Map.class));
    latest.put(taskId,item);
    if(latest.size()==4)break;
   }catch(BusinessException unavailable){if(unavailable.httpStatus()!=404)throw unavailable;}
   catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
    throw new BusinessException("CONVERSATION_EVENT_INVALID","会话记录不可读取",503);
   }
  }
  return List.copyOf(latest.values());
 }
 public Map<String,Object> cancel(PrincipalInfo p,String id){return cancel(p,id,null);}
 public Map<String,Object> cancel(PrincipalInfo p,String id,String source){verify(p,id,source);Run r=active.get(id);if(r==null)throw new BusinessException("NO_ACTIVE_RUN","当前没有正在处理的请求",409);finish(r,"run.aborted",Map.of("message","已停止"));bridge.cancel(r.runId);return Map.of("cancelled",true);}
 public AgentToolContext toolContext(String toolContextId,String conversationId,String turnId,String runId){
  Run r=toolContextId==null?null:toolContextsById.get(toolContextId);
  if(r==null||r.terminal.get()||!r.conversationId.equals(conversationId)||!r.turnId.equals(turnId)||!r.runId.equals(runId))
   throw new BusinessException("AGENT_TOOL_RUN_UNAVAILABLE","Agent 运行上下文不可用或不匹配",409);
  return r.context;
 }
 public List<String> retireDrafts(String toolContextId,String conversationId,String turnId,String runId){
  AgentToolContext context=toolContext(toolContextId,conversationId,turnId,runId);
  return drafts.retireForConversation(context.principal(),conversationId,context.source());
 }
 /** Runtime loadActionDraftContext parity: latest draft plus last execution outcome, post-retire truth. */
 public Map<String,Object> draftContext(String toolContextId,String conversationId,String turnId,String runId){
  AgentToolContext context=toolContext(toolContextId,conversationId,turnId,runId);
  Map<String,Object> latest=null;Map<String,Object> lastExecution=null;
  for(Map<String,Object> view:drafts.conversationDrafts(context.principal(),conversationId,context.source()).values()){
   Map<String,Object> fact=new LinkedHashMap<>();
   fact.put("draftId",view.get("draftId"));fact.put("status",view.get("status"));fact.put("summary",view.getOrDefault("summary",""));
   latest=fact;
   if(List.of("ACCEPTED","PARTIALLY_ACCEPTED","REJECTED","NO_CHANGE","INTERRUPTED").contains(String.valueOf(view.get("status"))))lastExecution=fact;
  }
  Map<String,Object> result=new LinkedHashMap<>();
  result.put("latestDraft",latest);result.put("lastExecution",lastExecution);
  return result;
 }
 public List<String> reportArtifactIds(String toolContextId,String conversationId,String turnId,String runId){
  toolContext(toolContextId,conversationId,turnId,runId);
  Set<String> ids=new LinkedHashSet<>();
  for(ConversationEventFact event:events.read(conversationId)){
   if(!"observation.reported".equals(event.type()))continue;
   try{
    var root=mapper.readTree(event.payloadJson());
    for(var block:root.path("blocks")){
     if("report_artifact".equals(block.path("type").asText())){
      String id=block.path("report").path("id").asText("");
      if(!id.isBlank())ids.add(id);
     }
    }
   }catch(Exception invalid){throw new BusinessException("REPORT_HISTORY_UNAVAILABLE","历史报告身份暂不可读取",503);}
  }
  return List.copyOf(ids);
 }
 private void fail(Run r,Throwable failure){
  synchronized(r){
   if(r.terminal.get())return;
   if(r.sequence==0)emit(r,"run.started",Map.of("createdAt",Instant.now().toString()));
   Throwable cause=failure instanceof CompletionException&&failure.getCause()!=null?failure.getCause():failure;
   if(cause instanceof BusinessException business)finish(r,"run.failed",Map.of("code",business.code(),"message",business.getMessage()));
   else finish(r,"run.failed",Map.of("code","AGENT_ENGINE_UNAVAILABLE","message","Agent Engine 暂不可用"));
  }
 }
 private void emit(Run r,String type,Map<String,Object> f){
  if("heartbeat".equals(type)){sendHeartbeat(r);return;}
  if(TERMINALS.contains(type)){finish(r,type,f);return;}
  long waitingAt=System.nanoTime();
  synchronized(r){if(r.terminal.get())return;synchronized(events){
   r.lockNanos+=System.nanoTime()-waitingAt;
   flushRecordedEvents(r);ObjectNode n=node(r,type,f);append(r,n);send(r,n);
   if("message.completed".equals(type)&&f!=null&&f.get("content") instanceof String content&&!content.isBlank())r.lastPublicReply=content;}}
 }
 private void finish(Run r,String type,Map<String,Object> f){
  synchronized(r){
   if(!r.terminal.compareAndSet(false,true))return;
   try{synchronized(events){
    flushRecordedEvents(r);
    if(r.confirmationTaskId!=null&&("run.failed".equals(type)||"run.aborted".equals(type))){
     ObjectNode decision=node(r,"task.confirmation",Map.of("taskId",r.confirmationTaskId,
       "confirmation",Map.of("status","run.aborted".equals(type)?"stopped":"blocked",
       "message","原任务核实已中断，目标与必要决定已保留")));append(r,decision);send(r,decision);
    }
    ObjectNode n=node(r,type,f);append(r,n);send(r,n);
    Map<String,Object> audited=new LinkedHashMap<>();audited.put("action","message");audited.put("status",type);
    if(r.lastPublicReply!=null)audited.put("reply",r.lastPublicReply);
    audit(r,"AGENT_"+type.replace('.', '_').toUpperCase(Locale.ROOT),audited);
   }}
   catch(RuntimeException error){send(r,node(r,"run.failed",Map.of("code","CONVERSATION_PERSISTENCE_UNAVAILABLE","message","会话记录未能保存，请稍后重试")));}
   finally{
    AgentRunTiming.delivery(r.runId,Map.of("outcome",type,"totalMs",(System.nanoTime()-r.receivedAt)/1_000_000.0,
      "lockWaitMs",r.lockNanos/1_000_000.0,"historyReadMs",r.historyReadNanos/1_000_000.0,
      "appendMs",r.appendNanos/1_000_000.0,"sendMs",r.sendNanos/1_000_000.0,
      "appendCount",r.appendCount,"sendCount",r.sendCount,"firstTextSentMs",r.firstTextSentMs));
    if(r.heartbeat!=null)r.heartbeat.cancel(false);try{r.out.complete();}catch(Exception ignored){}active.remove(r.conversationId,r);toolContextsById.remove(r.toolContextId,r);
   }
  }
  }
 private void audit(Run r,String type,Map<String,Object> fields){
  if(audit==null)return;
  Map<String,Object> payload=new LinkedHashMap<>(fields);
  payload.put("source",r.context.source());
  payload.put("principalId",r.context.principal().principalId());
  payload.put("identityRealm",r.context.principal().identityRealm());
  payload.put("conversationId",r.conversationId);
  payload.put("turnId",r.turnId);
  payload.put("runId",r.runId);
  payload.put("at",Instant.now().toString());
  try{audit.append(new AuditEventFact(UUID.randomUUID().toString(),r.context.principal().tenantCode(),type,mapper.writeValueAsString(payload)));}
  catch(Exception failure){throw new BusinessException("AUDIT_UNAVAILABLE","审计记录未能保存，请稍后重试",503);}
 }
 private Map<String,Object> requested(String content){
  Map<String,Object> fields=new LinkedHashMap<>();fields.put("action","message");fields.put("status","started");
  if(content!=null&&!content.isBlank())fields.put("request",bounded(content));
  return fields;
 }
 /** The audit copy carries only the member's public request text and the run's public reply, never engine internals. */
 private static String bounded(String text){return text.length()<=2000?text:text.substring(0,2000)+"…（已截断）";}
 private void flushRecordedEvents(Run r){
  List<ConversationEventFact> recordedFacts;
  long readingAt=System.nanoTime();
  try{recordedFacts=events.read(r.conversationId);}
  finally{r.historyReadNanos+=System.nanoTime()-readingAt;}
  for(ConversationEventFact fact:recordedFacts.stream()
    .filter(event->r.turnId.equals(event.turnId())&&event.sequence()>r.sequence)
    .sorted(Comparator.comparingLong(ConversationEventFact::sequence)).toList()){
   try{
    if(!(mapper.readTree(fact.payloadJson()) instanceof ObjectNode recorded))
     throw new IllegalStateException("Conversation event must be an object");
    recorded.put("type",fact.type());recorded.put("sequence",fact.sequence());
    recorded.put("conversationId",r.conversationId);recorded.put("turnId",r.turnId);
    r.sequence=fact.sequence();send(r,recorded);
   }catch(com.fasterxml.jackson.core.JsonProcessingException error){
    throw new BusinessException("CONVERSATION_EVENT_INVALID","会话记录不可读取",503);
   }
  }
 }
 private void scheduleHeartbeat(Run r){
  long heartbeat=heartbeatMs.getAsLong();
  if(scheduler!=null&&heartbeat>0)r.heartbeat=scheduler.scheduleAtFixedRate(()->sendHeartbeat(r),heartbeat,heartbeat,TimeUnit.MILLISECONDS);
 }
 private void sendHeartbeat(Run r){
  synchronized(r){if(!r.terminal.get()){ObjectNode n=mapper.createObjectNode();n.put("type","heartbeat");send(r,n);}}
 }
 private ObjectNode node(Run r,String type,Map<String,Object> f){
  ObjectNode n=mapper.createObjectNode();n.put("type",type);n.put("sequence",++r.sequence);n.put("conversationId",r.conversationId);n.put("turnId",r.turnId);n.put("timestamp",Instant.now().toString());
  f.forEach((k,v)->{if(!Set.of("type","sequence","conversationId","turnId","timestamp").contains(k))n.set(k,mapper.valueToTree(v));});
  return n;
 }
 private void append(Run r,ObjectNode n){
  long started=System.nanoTime();
  try{events.append(new ConversationEventFact(r.conversationId,r.turnId,r.sequence,n.get("type").asText(),n.toString(),r.runId+":"+r.sequence));}
  finally{r.appendNanos+=System.nanoTime()-started;r.appendCount++;}
 }
 private void send(Run r,ObjectNode n){
  if(r.disconnected.get())return;
  long started=System.nanoTime();
  try{
   r.out.send(n.toString()+"\n");
   if(r.firstTextSentMs<0&&"message.delta".equals(n.path("type").asText())&&!n.path("content").asText("").isBlank())
    r.firstTextSentMs=(System.nanoTime()-r.receivedAt)/1_000_000.0;
  }catch(Exception e){r.disconnected.set(true);}
  finally{r.sendNanos+=System.nanoTime()-started;r.sendCount++;}
 }
 private Map<String,Object> turn(ConversationTurn t,List<ConversationEventFact> facts,Map<String,Map<String,Object>> authoritative,PrincipalInfo principal,String source){
  List<Object> es=new ArrayList<>(facts.size());
  ObjectNode draft=null,result=null;
  Map<String,ObjectNode> tasks=new LinkedHashMap<>();
  if(facts.size()>1)facts.sort(Comparator.comparingLong(ConversationEventFact::sequence));
  for(ConversationEventFact f:facts){
   try{
    if(!(mapper.readTree(f.payloadJson()) instanceof ObjectNode recorded))continue;
    es.add(mapper.convertValue(recorded,Map.class));
    String draftId=recorded.path("draftId").asText("");
    if("action.proposed".equals(f.type())&&!draftId.isBlank()){
     draft=recorded.deepCopy();
    }else if("action.resolved".equals(f.type())&&draft!=null&&draftId.equals(draft.path("draftId").asText(""))){
     draft.put("status",recorded.path("status").asText(draft.path("status").asText("PENDING_CONFIRMATION")));
     if(recorded.path("revision").isNumber())draft.put("revision",recorded.path("revision").asLong());
     if(recorded.path("result").isObject()){result=(ObjectNode)recorded.get("result").deepCopy();draft.set("result",result.deepCopy());}
    }
    if(recorded.path("task").isObject()){
     ObjectNode task=(ObjectNode)recorded.get("task").deepCopy();
     String key=task.path("changeId").asText(task.path("cancellationId").asText(task.path("taskId").asText("")));
     if(!key.isBlank())tasks.put(key,task);
    }
    if("task.resolved".equals(f.type())&&!recorded.path("taskId").asText("").isBlank()){
     ObjectNode task=tasks.get(recorded.path("taskId").asText());
     if(task!=null)task.put("status",recorded.path("status").asText(task.path("status").asText("")));
    }else if("task.change.resolved".equals(f.type())&&!recorded.path("changeId").asText("").isBlank()){
     ObjectNode task=tasks.get(recorded.path("changeId").asText());
     if(task!=null)task.put("status",recorded.path("status").asText(task.path("status").asText("")));
    }else if("task.cancellation.resolved".equals(f.type())&&!recorded.path("cancellationId").asText("").isBlank()){
     ObjectNode task=tasks.get(recorded.path("cancellationId").asText());
     if(task!=null)task.put("status",recorded.path("status").asText(task.path("status").asText("")));
    }
   }catch(Exception ignored){}
  }
  Map<String,Object> view=new LinkedHashMap<>();
  view.put("id",t.id());view.put("content",t.content());view.put("startedAt",t.startedAt().toString());view.put("events",es);
  if(draft!=null){Map<String,Object> current=authoritative.get(draft.path("draftId").asText(""));if(current!=null){view.put("draft",current);if(current.get("result")!=null)view.put("result",current.get("result"));}}
  if(!tasks.isEmpty()){
   List<Map<String,Object>> list=new ArrayList<>();
   for(Map.Entry<String,ObjectNode> entry:tasks.entrySet()){
    ObjectNode task=entry.getValue();
    if(!task.path("created").asBoolean(false)){
     Map<String,Object> current=Objects.requireNonNull(taskCards.conversationProposalState(principal,entry.getKey(),source));
     current.forEach((key,value)->task.set(key,mapper.valueToTree(value)));
    }
    list.add(mapper.convertValue(task,Map.class));
   }
   view.put("tasks",list);view.put("task",list.get(0));
  }
  return view;
 }
 private ConversationRecord verify(PrincipalInfo p,String id,String source){
  ConversationRecord conversation=store.find(id,p.tenantCode(),owner(p)).orElseThrow(()->new BusinessException("CONVERSATION_UNAVAILABLE","会话已过期或不可用，请清除后重新开始",404));
  if(conversation.cleared()||!conversation.lastActiveAt().isAfter(Instant.now().minus(Duration.ofHours(6))))throw new BusinessException("CONVERSATION_UNAVAILABLE","会话已过期或不可用，请清除后重新开始",404);
  String effectiveSource=source==null?"office":source;validSource(effectiveSource);if(!effectiveSource.equals(conversation.source()))throw new BusinessException("CONVERSATION_SOURCE_MISMATCH","会话来源与当前请求不一致",404);
  return conversation;
}
 private static void validSource(String s){if(!s.equals("office")&&!s.equals("company"))throw new BusinessException("INVALID_AGENT_SOURCE","Agent 来源无效");}
 private static String owner(PrincipalInfo p){return p.ownerKey();}
 private static final class Run{
  final String conversationId,turnId,runId,toolContextId;final AgentToolContext context;final ResponseBodyEmitter out;final AtomicBoolean terminal=new AtomicBoolean();final AtomicBoolean disconnected=new AtomicBoolean();long sequence;ScheduledFuture<?> heartbeat;
  boolean continuation;String confirmationTaskId;String lastPublicReply;
  long receivedAt=System.nanoTime(),lockNanos,historyReadNanos,appendNanos,sendNanos,appendCount,sendCount;
  double firstTextSentMs=-1;
  Run(String c,String t,String r,String tc,PrincipalInfo p,String s,String focus,Map<String,Object> reference,ResponseBodyEmitter o){conversationId=c;turnId=t;runId=r;toolContextId=tc;context=new AgentToolContext(p,r,c,t,s,focus,reference);out=o;o.onError(x->disconnected.set(true));o.onCompletion(()->disconnected.set(true));}
 }
}
