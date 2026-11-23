package com.chaos.service.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 统一登记事务提交后或回滚后的副作用。
 *
 * <p>提交后的动作在独立于业务事务的时机执行，默认异常只记录日志，不影响已经提交的业务；
 * 回滚动作只在当前事务确实回滚时执行，用于清理本次已经写入的外部对象（例如先存储后入库的临时文件）。</p>
 */
public class TransactionalSideEffects {

    private static final Logger log = LoggerFactory.getLogger(TransactionalSideEffects.class);

    /**
     * 在当前事务提交后执行动作；当前没有事务时立即执行。
     *
     * <p>动作异常只记录错误日志，不向调用方传播，也不改变已经提交的事务结果。</p>
     *
     * @param action 提交后需要执行的副作用；不得为 {@code null}
     */
    public void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runQuietly("事务提交后", action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runQuietly("事务提交后", action);
                    }
                });
    }

    /**
     * 在当前事务提交后执行动作并让异常向调用方传播；当前没有事务时立即执行。
     *
     * <p>用于跨服务通知等必须让调用方感知失败的业务动作；普通清理动作应使用 {@link #afterCommit(Runnable)}。</p>
     *
     * @param action 提交后需要执行且失败必须上报的副作用；不得为 {@code null}
     */
    public void afterCommitPropagating(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    /**
     * 判断当前线程是否处于事务同步状态。
     *
     * @return 当前存在活动事务同步时返回 {@code true}
     */
    public boolean hasActiveTransaction() {
        return TransactionSynchronizationManager.isSynchronizationActive();
    }

    /**
     * 在当前事务回滚后执行清理动作；当前没有事务时忽略。
     *
     * <p>动作异常只记录错误日志，避免覆盖原始业务异常。</p>
     *
     * @param action 回滚后需要执行的清理动作；不得为 {@code null}
     */
    public void onRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            runQuietly("事务回滚后", action);
                        }
                    }
                });
    }

    private static void runQuietly(String phase, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.error("{}副作用执行失败，请人工核对", phase, ex);
        }
    }
}
