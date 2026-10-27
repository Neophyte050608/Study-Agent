package io.github.imzmq.interview.agent.infrastructure.persistence.execution;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** Every mutation is invoked under the task row lock in ExecutionTransactions. DB time owns leases. */
@Mapper
public interface ExecutionMapper {
    @Insert("""
        INSERT INTO t_agent_execution_task
        (task_id,owner_user_id,workflow_type,workflow_version,idempotency_key,input_hash,input_json,definition_json,status)
        VALUES (#{taskId},#{ownerUserId},#{workflowType},#{workflowVersion},#{idempotencyKey},#{inputHash},#{inputJson},#{definitionJson},'QUEUED')
        """)
    int insertTask(ExecutionTaskDO task);
    @Select("SELECT * FROM t_agent_execution_task WHERE owner_user_id=#{owner} AND workflow_type=#{type} AND idempotency_key=#{key} FOR UPDATE")
    ExecutionTaskDO byKey(@Param("owner") String owner, @Param("type") String type, @Param("key") String key);
    @Insert("INSERT INTO t_agent_execution_node(task_id,node_id,status) VALUES(#{task},#{node},'PENDING')")
    int insertNode(@Param("task") String task, @Param("node") String node);
    @Select("SELECT * FROM t_agent_execution_task WHERE owner_user_id=#{owner} AND task_id=#{task} FOR UPDATE")
    ExecutionTaskDO owned(@Param("owner") String owner, @Param("task") String task);
    @Select("SELECT * FROM t_agent_execution_node WHERE task_id=#{task} ORDER BY node_id")
    List<ExecutionNodeDO> nodes(String task);
    @Select("""
        SELECT * FROM t_agent_execution_task
        WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6))
        ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
        """)
    ExecutionTaskDO claimable();
    @Update("""
        UPDATE t_agent_execution_task SET status='RUNNING', lease_owner=#{worker}, lease_epoch=lease_epoch+1,
        lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)),
        run_deadline=COALESCE(run_deadline,TIMESTAMPADD(SECOND,75,CURRENT_TIMESTAMP(6))),updated_at=CURRENT_TIMESTAMP(6)
        WHERE task_id=#{task}
        """)
    int lease(@Param("task") String task, @Param("worker") String worker, @Param("seconds") int seconds);
    @Select("SELECT * FROM t_agent_execution_task WHERE task_id=#{task}")
    ExecutionTaskDO task(String task);
    @Select("""
        SELECT * FROM t_agent_execution_task WHERE task_id=#{task} AND status='RUNNING'
        AND lease_owner=#{worker} AND lease_epoch=#{epoch} AND lease_until>CURRENT_TIMESTAMP(6) FOR UPDATE
        """)
    ExecutionTaskDO fenced(@Param("task") String task, @Param("worker") String worker, @Param("epoch") long epoch);
    // Recheck time in a fresh statement after acquiring the row lock: lock waiting must not revive a lease.
    @Select("SELECT COUNT(*) FROM t_agent_execution_task WHERE task_id=#{task} AND status='RUNNING' AND lease_owner=#{worker} AND lease_epoch=#{epoch} AND lease_until>CURRENT_TIMESTAMP(6)")
    int liveLease(@Param("task") String task, @Param("worker") String worker, @Param("epoch") long epoch);
    @Select("SELECT GREATEST(0,TIMESTAMPDIFF(MICROSECOND,CURRENT_TIMESTAMP(6),run_deadline)) FROM t_agent_execution_task WHERE task_id=#{task}")
    long remainingMicros(String task);
    @Update("""
        UPDATE t_agent_execution_task SET lease_until=TIMESTAMPADD(SECOND,#{seconds},CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6)
        WHERE task_id=#{task} AND status='RUNNING' AND lease_owner=#{worker} AND lease_epoch=#{epoch}
        AND lease_until>CURRENT_TIMESTAMP(6)
        """)
    int heartbeat(@Param("task") String task, @Param("worker") String worker, @Param("epoch") long epoch, @Param("seconds") int seconds);
    @Update("""
        UPDATE t_agent_execution_attempt SET status='ABANDONED', error_code='LEASE_LOST',finished_at=CURRENT_TIMESTAMP(6)
        WHERE task_id=#{task} AND status='RUNNING'
        """)
    int abandon(String task);
    @Update("""
        UPDATE t_agent_execution_node SET status=IF(attempt_count<#{maximum},'PENDING','FAILED'),
        error_code=IF(attempt_count<#{maximum},NULL,'ATTEMPT_BUDGET_EXHAUSTED'),current_attempt_id=NULL,output_json=NULL
        WHERE task_id=#{task} AND status='RUNNING'
        """)
    int recover(@Param("task") String task, @Param("maximum") int maximum);
    @Insert("""
        INSERT INTO t_agent_execution_attempt(attempt_id,task_id,node_id,attempt_number,revision,lease_epoch,input_json,status)
        VALUES(#{attempt},#{task},#{node},#{number},#{revision},#{epoch},#{input},'RUNNING')
        """)
    int startAttempt(@Param("attempt") String attempt, @Param("task") String task, @Param("node") String node,
                     @Param("number") int number, @Param("revision") int revision, @Param("epoch") long epoch, @Param("input") String input);
    @Update("""
        UPDATE t_agent_execution_node SET status='RUNNING',attempt_count=attempt_count+1,current_attempt_id=#{attempt},error_code=NULL
        WHERE task_id=#{task} AND node_id=#{node} AND status='PENDING' AND attempt_count<#{maximum}
        """)
    int startNode(@Param("task") String task, @Param("node") String node, @Param("attempt") String attempt, @Param("maximum") int maximum);
    @Update("""
        UPDATE t_agent_execution_attempt SET status=#{status},output_json=#{output},error_code=#{code},finished_at=CURRENT_TIMESTAMP(6)
        WHERE attempt_id=#{attempt} AND status='RUNNING'
        """)
    int finishAttempt(@Param("attempt") String attempt, @Param("status") String status, @Param("output") String output, @Param("code") String code);
    @Update("""
        UPDATE t_agent_execution_node SET status=#{status},output_json=#{output},error_code=#{code}
        WHERE task_id=#{task} AND node_id=#{node}
        """)
    int finishNode(@Param("task") String task, @Param("node") String node, @Param("status") String status, @Param("output") String output, @Param("code") String code);
    @Update("""
        UPDATE t_agent_execution_task SET status=#{status},final_result_json=#{result},error_code=#{code},
        lease_owner=NULL,lease_until=NULL,finished_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)
        WHERE task_id=#{task}
        """)
    int finishTask(@Param("task") String task, @Param("status") String status, @Param("result") String result, @Param("code") String code);
    @Update("UPDATE t_agent_execution_node SET status='PENDING',current_attempt_id=NULL,output_json=NULL,error_code=NULL WHERE task_id=#{task} AND node_id=#{node}")
    int resetNode(@Param("task") String task, @Param("node") String node);
    @Update("""
        UPDATE t_agent_execution_task SET status='QUEUED',revision=revision+1,run_deadline=NULL,final_result_json=NULL,
        error_code=NULL,finished_at=NULL,lease_owner=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE task_id=#{task}
        """)
    int retry(String task);
    @Delete("DELETE FROM t_agent_execution_task WHERE task_id=#{task}")
    int delete(String task);
}
