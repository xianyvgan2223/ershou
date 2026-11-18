package com.ershou.cloud.platform.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务钩子：把缓存失效等旁路动作放到事务提交之后执行（与单体 common.TxHooks 语义一致）。
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
