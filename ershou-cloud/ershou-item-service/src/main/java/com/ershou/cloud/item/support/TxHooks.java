package com.ershou.cloud.item.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务钩子：把"索引同步"这类旁路动作放到事务提交之后执行，
 * 避免业务回滚了、ES 里却留下脏文档。
 */
public final class TxHooks {

    private TxHooks() {
    }

    public static void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
