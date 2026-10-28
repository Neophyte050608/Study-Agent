package io.github.imzmq.interview.agent.application.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.agent.application.contract.execution.durable.*;
import io.github.imzmq.interview.agent.infrastructure.persistence.execution.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, disposable database only. No live model or developer schema. */
class DurableExecutionMySqlIntegrationTest {
    private DataSource ds;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ExecutionTransactions tx;
    private ExecutionMapper mapper;
    private ExecutionJson json;
    private DurableExecutionService service;
    private DurableWorkflowRegistry registry;
    private final AtomicInteger profileCalls = new AtomicInteger();
    private final AtomicInteger evidenceCalls = new AtomicInteger();
    private final AtomicInteger composeCalls = new AtomicInteger();
    private boolean failEvidence;
    private static final Map<String,Set<String>> DEPS = Map.of("profile",Set.of(),"evidence",Set.of(),"compose",Set.of("profile","evidence"));

    @BeforeEach void setup() throws Exception {
        String url=System.getProperty("test.mysql.execution-jdbc-url", "");
        assumeTrue(!url.isBlank(), "set test.mysql.execution-jdbc-url to an isolated disposable database");
        ds=new DriverManagerDataSource(url,System.getProperty("test.mysql.username","root"),System.getProperty("test.mysql.password",""));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        jdbc=new JdbcTemplate(ds);transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ExecutionMapper.class);factory.setConfiguration(config);
        SqlSessionFactory sessions=factory.getObject();
        mapper=new SqlSessionTemplate(sessions).getMapper(ExecutionMapper.class);
        json=new ExecutionJson(new ObjectMapper());tx=new ExecutionTransactions(mapper,json);
        registry=new DurableWorkflowRegistry(List.of(new DurableWorkflow() {
            public String type() {return "TEST_PREVIEW";} public int version(){return 1;}
            public Prepared prepare(String owner,String input) {return new Prepared(definition(),graph());}
            public List<DagNode> restore(String owner,String input,String definition) {
                if(!definition().equals(json.check(definition,65536))) throw new IllegalArgumentException("WORKFLOW_DEFINITION_MISMATCH");
                return graph();
            }
            private List<DagNode> graph() {
                return List.of(new DagNode("profile",Set.of(),false,i->{profileCalls.incrementAndGet();return "profile";}),
                        new DagNode("evidence",Set.of(),false,i->{evidenceCalls.incrementAndGet();if(failEvidence)throw new IllegalStateException();return "evidence";}),
                        new DagNode("compose",Set.of("profile","evidence"),false,i->{composeCalls.incrementAndGet();return "plan";}));
            }
            private String definition() {
                return json.write(List.of(
                        Map.of("id","compose","dependencies",List.of("evidence","profile"),"requireSuccess",false),
                        Map.of("id","evidence","dependencies",List.of(),"requireSuccess",false),
                        Map.of("id","profile","dependencies",List.of(),"requireSuccess",false)),65536);
            }
            public String encodeOutput(String id,Object value){return json.write(value,65536);}
            public Object decodeOutput(String id,String value){try{return new ObjectMapper().readValue(value,String.class);}catch(Exception e){throw new IllegalArgumentException();}}
            public WorkflowOutcome outcome(DagExecutionResult result){return new WorkflowOutcome(result.code().equals("COMPLETED")?"COMPLETED":"DEGRADED","{\"plan\":\"safe\"}");}
        }));
        service=new DurableExecutionService(tx,registry,json,true);
    }
    @AfterEach void cleanup() {
        if(jdbc!=null) jdbc.update("DELETE FROM t_agent_execution_task WHERE workflow_type='TEST_PREVIEW'");
    }
    private <T>T atomic(Supplier<T> action){return transaction.execute(s->action.get());}
    private void atomic(Runnable action){transaction.executeWithoutResult(s->action.run());}
    private DurableTaskView submit(String owner,String key,String input){return atomic(()->service.submit(owner,"TEST_PREVIEW",1,key,input));}
    private ExecutionTaskDO claim(String owner){return atomic(()->tx.claim(owner,30));}
    private DagNodeResult success(){return new DagNodeResult(DagNodeStatus.SUCCEEDED,"saved","OK");}
    private void expire(ExecutionTaskDO task){jdbc.update("UPDATE t_agent_execution_task SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE task_id=?",task.getTaskId());}

    @Test void idempotencyOwnerIsolationAndCascadeDeletion() {
        var original=submit("owner","key","{\"a\":1,\"b\":2}");
        assertThat(submit("owner","key","{\"b\":2,\"a\":1}").taskId()).isEqualTo(original.taskId());
        assertThatThrownBy(()->submit("owner","key","{\"a\":2}")).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(submit("Owner","key","{\"a\":1,\"b\":2}").taskId()).isNotEqualTo(original.taskId());
        assertThatThrownBy(()->atomic(()->tx.query("stranger",original.taskId()))).hasMessage("EXECUTION_NOT_FOUND");
        assertThatThrownBy(()->atomic(()->tx.delete("owner",original.taskId()))).hasMessage("EXECUTION_NOT_TERMINAL");
        var task=claim("worker");atomic(()->tx.start(task,"profile","{}"));
        atomic(()->tx.finish(task,"FAILED",null,"EXECUTION_FAILED"));
        atomic(()->tx.delete(task.getOwnerUserId(),task.getTaskId()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_agent_execution_attempt WHERE task_id=?",Long.class,task.getTaskId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_agent_execution_node WHERE task_id=?",Long.class,task.getTaskId())).isZero();
    }
    @Test void concurrentDuplicateSubmissionCreatesExactlyOneGraph() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return submit("owner","duplicate","{\"a\":1,\"b\":2}");});
            var b=pool.submit(()->{gate.await();return submit("owner","duplicate","{\"b\":2,\"a\":1}");});
            gate.countDown();var first=a.get(5,TimeUnit.SECONDS);var second=b.get(5,TimeUnit.SECONDS);
            assertThat(first.taskId()).isEqualTo(second.taskId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_agent_execution_node WHERE task_id=?",Long.class,first.taskId())).isEqualTo(3);
        }
    }
    @Test void concurrentClaimAndTakeoverFenceEveryOldMutationWithoutRenewingDeadline() throws Exception {
        submit("owner","lease","{}");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();return claim("a");});var b=pool.submit(()->{gate.await();return claim("b");});gate.countDown();
            var first=a.get(5,TimeUnit.SECONDS);var other=b.get(5,TimeUnit.SECONDS);
            assertThat((first==null)^(other==null)).isTrue();var old=first==null?other:first;
            atomic(()->tx.start(old,"profile","{}"));atomic(()->tx.complete(old,"profile",success(),"\"saved\""));
            atomic(()->tx.start(old,"evidence","{}"));expire(old);
            var next=claim("new");assertThat(next.getLeaseEpoch()).isEqualTo(old.getLeaseEpoch()+1);
            assertThat(next.getRunDeadline()).isEqualTo(old.getRunDeadline());
            assertThat(atomic(()->tx.heartbeat(old,30))).isFalse();
            assertThatThrownBy(()->atomic(()->tx.start(old,"compose","{}"))).hasMessage("EXECUTION_LEASE_LOST");
            assertThatThrownBy(()->atomic(()->tx.complete(old,"evidence",success(),"\"late\""))).hasMessage("EXECUTION_LEASE_LOST");
            assertThatThrownBy(()->atomic(()->tx.finish(old,"FAILED",null,"FAILED"))).hasMessage("EXECUTION_LEASE_LOST");
            var states=atomic(()->tx.snapshot(next));
            assertThat(states.stream().filter(n->n.getNodeId().equals("profile")).findFirst().orElseThrow().getOutputJson()).isEqualTo("\"saved\"");
            assertThat(jdbc.queryForObject("SELECT status FROM t_agent_execution_attempt WHERE task_id=? AND node_id='evidence'",String.class,next.getTaskId())).isEqualTo("ABANDONED");
            assertThatThrownBy(()->atomic(()->tx.finish(next,"COMPLETED","{}",null))).hasMessage("EXECUTION_INCOMPLETE");
            atomic(()->tx.start(next,"evidence","{}"));
            assertThatThrownBy(()->atomic(()->tx.complete(next,"evidence",success(),"not-json"))).hasMessage("INVALID_OR_OVERSIZED_EXECUTION_JSON");
            assertThat(jdbc.queryForObject("SELECT status FROM t_agent_execution_node WHERE task_id=? AND node_id='evidence'",String.class,next.getTaskId())).isEqualTo("RUNNING");
            atomic(()->tx.complete(next,"evidence",success(),"\"evidence\""));
            assertThat(jdbc.queryForObject("SELECT status FROM t_agent_execution_attempt WHERE task_id=? AND node_id='evidence' AND attempt_number=2",String.class,next.getTaskId())).isEqualTo("SUCCEEDED");
        }
    }
    @Test void attemptUpdateRollsBackIfNodeCommitFails() {
        submit("owner","atomic","{}");var task=claim("w");atomic(()->tx.start(task,"profile","{}"));
        var failing=org.mockito.Mockito.spy(mapper);
        org.mockito.Mockito.doReturn(0).when(failing).finishNode(task.getTaskId(),"profile","SUCCEEDED","\"saved\"","OK");
        var broken=new ExecutionTransactions(failing,json);
        assertThatThrownBy(()->atomic(()->broken.complete(task,"profile",success(),"\"saved\""))).hasMessage("NODE_STATE_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT status FROM t_agent_execution_attempt WHERE task_id=? AND node_id='profile'",String.class,task.getTaskId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM t_agent_execution_node WHERE task_id=? AND node_id='profile'",String.class,task.getTaskId())).isEqualTo("RUNNING");
    }
    @Test void leaseExpiringWhileWaitingForRowLockCannotCommit() throws Exception {
        submit("owner","lock","{}");var task=claim("w");atomic(()->tx.start(task,"profile","{}"));
        jdbc.update("UPDATE t_agent_execution_task SET lease_until=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6)) WHERE task_id=?",task.getTaskId());
        try(var connection=ds.getConnection();var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT task_id FROM t_agent_execution_task WHERE task_id=? FOR UPDATE")) {
                lock.setString(1,task.getTaskId());try(var result=lock.executeQuery()){assertThat(result.next()).isTrue();}
            }
            var started=new CountDownLatch(1);
            var result=pool.submit(()->{started.countDown();try{atomic(()->tx.complete(task,"profile",success(),"\"late\""));return "UNSAFE";}catch(IllegalStateException e){return e.getMessage();}});
            try {assertThat(started.await(1,TimeUnit.SECONDS)).isTrue();Thread.sleep(1500);}
            finally {connection.commit();}
            assertThat(result.get(5,TimeUnit.SECONDS)).isEqualTo("EXECUTION_LEASE_LOST");
            assertThat(jdbc.queryForObject("SELECT status FROM t_agent_execution_node WHERE task_id=? AND node_id='profile'",String.class,task.getTaskId())).isEqualTo("RUNNING");
        }
    }
    @Test void deadlineRejectsLateOutputAndAttemptBudgetSurvivesTakeovers() {
        submit("owner","deadline","{}");var task=claim("w");atomic(()->tx.start(task,"profile","{}"));
        jdbc.update("UPDATE t_agent_execution_task SET run_deadline=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE task_id=?",task.getTaskId());
        assertThatThrownBy(()->atomic(()->tx.complete(task,"profile",success(),"\"late\""))).hasMessage("EXECUTION_DEADLINE");
        atomic(()->tx.finish(task,"COMPLETED","{}",null));
        assertThat(atomic(()->tx.query("owner",task.getTaskId())).status()).isEqualTo("TIMED_OUT");
        atomic(()->tx.retry("owner",task.getTaskId(),0,Set.of("profile"),DEPS));
        var two=claim("w2");atomic(()->tx.start(two,"profile","{}"));expire(two);
        var three=claim("w3");atomic(()->tx.start(three,"profile","{}"));expire(three);
        var four=claim("w4");
        assertThat(atomic(()->tx.snapshot(four)).stream().filter(n->n.getNodeId().equals("profile")).findFirst().orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThatThrownBy(()->atomic(()->tx.start(four,"profile","{}"))).hasMessage("ATTEMPT_BUDGET_EXHAUSTED");
    }
    @Test void durableGraphRecoveryAndConcurrentPartialRetryReuseSuccessfulSibling() throws Exception {
        failEvidence=true;var created=submit("owner","preview","{}");var task=claim("w");
        try(var dag=new FixedDagExecutor()) {
            // Proxy adds real short transactions around each callback, not around the graph/model action.
            var proxy=new org.springframework.aop.framework.ProxyFactory(tx);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor)i->transaction.execute(s->{try{return i.proceed();}catch(Throwable e){throw e instanceof RuntimeException r?r:new IllegalStateException(e);}}));
            var worker=new DurableExecutionWorker((ExecutionTransactions)proxy.getProxy(),service,registry,json,dag,new DurableExecutionProperties());
            try {
                worker.run(task);assertThat(atomic(()->tx.query("owner",created.taskId())).status()).isEqualTo("DEGRADED");
                assertThat(profileCalls).hasValue(1);assertThat(evidenceCalls).hasValue(1);assertThat(composeCalls).hasValue(1);
                try(var pool=Executors.newFixedThreadPool(2)) {
                    var gate=new CountDownLatch(1);var results=new ArrayList<Future<String>>();
                    for(int n=0;n<2;n++)results.add(pool.submit(()->{gate.await();try{atomic(()->service.retry("owner",created.taskId(),0,Set.of("evidence")));return "OK";}catch(IllegalStateException e){return e.getMessage();}}));
                    gate.countDown();assertThat(List.of(results.get(0).get(5,TimeUnit.SECONDS),results.get(1).get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK","REVISION_CONFLICT");
                }
                assertThat(atomic(()->tx.query("owner",created.taskId())).resultJson()).isNull();
                failEvidence=false;var retry=claim("w2");worker.run(retry);
                assertThat(atomic(()->tx.query("owner",created.taskId())).status()).isEqualTo("COMPLETED");
                assertThat(profileCalls).hasValue(1);assertThat(evidenceCalls).hasValue(2);assertThat(composeCalls).hasValue(2);
                // Lost terminal write: restore only task RUNNING, keep committed nodes; recovery must not call actions again.
                jdbc.update("UPDATE t_agent_execution_task SET status='RUNNING',lease_owner='lost',lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE task_id=?",created.taskId());
                worker.run(claim("w3"));assertThat(composeCalls).hasValue(2);
                assertThat(atomic(()->tx.query("owner",created.taskId())).status()).isEqualTo("COMPLETED");
                assertThat(jdbc.queryForObject("SELECT input_json FROM t_agent_execution_attempt WHERE task_id=? AND node_id='compose' AND attempt_number=2",String.class,created.taskId())).contains("taskInput","dependencies","profile","evidence");
            } finally {worker.close();}
        }
    }
}
