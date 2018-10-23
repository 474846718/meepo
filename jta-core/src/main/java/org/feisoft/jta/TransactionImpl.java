/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */

package org.feisoft.jta;

import org.apache.commons.lang3.StringUtils;
import org.feisoft.common.utils.ByteUtils;
import org.feisoft.common.utils.CommonUtils;
import org.feisoft.jta.resource.XATerminatorImpl;
import org.feisoft.jta.resource.XATerminatorOptd;
import org.feisoft.jta.strategy.CommonTransactionStrategy;
import org.feisoft.jta.strategy.LastResourceOptimizeStrategy;
import org.feisoft.jta.strategy.SimpleTransactionStrategy;
import org.feisoft.jta.strategy.VacantTransactionStrategy;
import org.feisoft.jta.supports.jdbc.LocalXAResource;
import org.feisoft.jta.supports.resource.CommonResourceDescriptor;
import org.feisoft.jta.supports.resource.LocalXAResourceDescriptor;
import org.feisoft.jta.supports.resource.RemoteResourceDescriptor;
import org.feisoft.jta.supports.resource.UnidentifiedResourceDescriptor;
import org.feisoft.jta.supports.wire.RemoteCoordinator;
import org.feisoft.transaction.*;
import org.feisoft.transaction.Transaction;
import org.feisoft.transaction.archive.TransactionArchive;
import org.feisoft.transaction.archive.XAResourceArchive;
import org.feisoft.transaction.internal.SynchronizationList;
import org.feisoft.transaction.internal.TransactionListenerList;
import org.feisoft.transaction.internal.TransactionResourceListenerList;
import org.feisoft.transaction.logging.TransactionLogger;
import org.feisoft.transaction.supports.TransactionListener;
import org.feisoft.transaction.supports.TransactionResourceListener;
import org.feisoft.transaction.supports.TransactionTimer;
import org.feisoft.transaction.supports.resource.XAResourceDescriptor;
import org.feisoft.transaction.xa.TransactionXid;
import org.feisoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.*;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.*;

public class TransactionImpl implements Transaction {

    static final Logger logger = LoggerFactory.getLogger(TransactionImpl.class);

    private transient boolean timing = true;

    private TransactionBeanFactory beanFactory;

    private TransactionStrategy transactionStrategy;

    private int transactionStatus;

    private int transactionTimeout;

    private int transactionVote;

    private Object transactionalExtra;

    private final TransactionContext transactionContext;

    private final TransactionResourceListenerList resourceListenerList = new TransactionResourceListenerList();

    private final Map<String, XAResourceArchive> applicationMap = new HashMap<String, XAResourceArchive>();

    private final Map<String, XAResourceArchive> participantMap = new HashMap<String, XAResourceArchive>();

    private XAResourceArchive participant; // last resource

    private final List<XAResourceArchive> participantList = new ArrayList<XAResourceArchive>();

    private final List<XAResourceArchive> nativeParticipantList = new ArrayList<XAResourceArchive>();

    private final List<XAResourceArchive> remoteParticipantList = new ArrayList<XAResourceArchive>();

    private final SynchronizationList synchronizationList = new SynchronizationList();

    private final TransactionListenerList transactionListenerList = new TransactionListenerList();

    public static ThreadLocal<Xid> currentXid = new ThreadLocal<Xid>();

    public TransactionImpl(TransactionContext txContext) {
        this.transactionContext = txContext;
    }

    public XAResourceDescriptor getResourceDescriptor(String identifier) {
        XAResourceArchive archive = this.participantMap.get(identifier);
        return archive == null ? null : archive.getDescriptor();
    }

    public XAResourceDescriptor getRemoteCoordinator(String application) {
        XAResourceArchive archive = this.applicationMap.get(application);
        return archive == null ? null : archive.getDescriptor();
    }

    public void setBeanFactory(TransactionBeanFactory tbf) {
        this.beanFactory = tbf;
    }

    public boolean isLocalTransaction() {
        return this.participantList.size() <= 1;
    }

    public synchronized int participantPrepare() throws RollbackRequiredException, CommitRequiredException {

        if (this.transactionStatus == Status.STATUS_ROLLEDBACK) {
            throw new RollbackRequiredException();
        } else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
            throw new RollbackRequiredException();
        } else if (this.transactionStatus == Status.STATUS_UNKNOWN) {
            throw new RollbackRequiredException();
        } else if (this.transactionStatus == Status.STATUS_NO_TRANSACTION) {
            // it's impossible
            throw new RollbackRequiredException();
        } else if (this.transactionStatus == Status.STATUS_PREPARED) {
            throw new CommitRequiredException();
        } else if (this.transactionStatus == Status.STATUS_COMMITTING) {
            throw new CommitRequiredException();
        } else if (this.transactionStatus == Status.STATUS_COMMITTED) {
            throw new CommitRequiredException();
        } /* else active, marked_rollback, preparing {} */

        // stop-timing
        TransactionTimer transactionTimer = beanFactory.getTransactionTimer();
        transactionTimer.stopTiming(this);

        // before-completion
        this.synchronizationList.beforeCompletion();

        // delist all resources
        try {
            this.delistAllResource();
        } catch (RollbackRequiredException rrex) {
            this.transactionStatus = Status.STATUS_ROLLING_BACK;
            throw new RollbackRequiredException();
        } catch (SystemException ex) {
            this.transactionStatus = Status.STATUS_ROLLING_BACK;
            throw new RollbackRequiredException();
        } catch (RuntimeException rex) {
            this.transactionStatus = Status.STATUS_ROLLING_BACK;
            throw new RollbackRequiredException();
        }

        TransactionXid xid = this.transactionContext.getXid();

        TransactionArchive archive = this.getTransactionArchive();

        TransactionLogger transactionLogger = beanFactory.getTransactionLogger();
        this.transactionStatus = Status.STATUS_PREPARING;
        archive.setStatus(this.transactionStatus);
        transactionLogger.createTransaction(archive);
        this.transactionListenerList.onPrepareStart(xid);

        if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            // this.transactionListenerList.onPrepareSuccess(xid);
            throw new RollbackRequiredException();
        }

        try {
            TransactionStrategy currentStrategy = this.getTransactionStrategy();
            int vote = 0;
            this.transactionStatus = Status.STATUS_PREPARED;
            archive.setStatus(this.transactionStatus);
            this.transactionVote = vote;
            archive.setVote(vote);

            this.transactionListenerList.onPrepareSuccess(xid);

            return vote;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            transactionLogger.updateTransaction(archive);
        }

        return 0;

    }

    public synchronized void recoveryCommit() throws CommitRequiredException, SystemException {
        TransactionXid xid = this.transactionContext.getXid();
        try {
            this.recoverIfNecessary(); // Recover if transaction is recovered from tx-log.

            this.invokeParticipantCommit();
        } catch (HeuristicMixedException ex) {
            logger.error("[{}] recover: branch={}, status= mixed, message= {}",
                    ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
                    ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage(), ex);
            SystemException sysEx = new SystemException();
            sysEx.initCause(ex);
            throw sysEx;
        } catch (HeuristicRollbackException ex) {
            logger.error("[{}] recover: branch={}, status= rolledback",
                    ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
                    ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex);
            SystemException sysEx = new SystemException();
            sysEx.initCause(ex);
            throw sysEx;
        }
    }

    /* opc: true, compensable-transaction & remote-coordinator; false, remote-coordinator */
    public synchronized void participantCommit(boolean opc)
            throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
            IllegalStateException, CommitRequiredException, SystemException {
        if (this.transactionContext.isRecoveried()) {
            this.recover(); // Execute recoveryInit if transaction is recovered from tx-log.
            this.invokeParticipantCommit();
        } else if (opc) {
            this.participantOnePhaseCommit();
        } else {
            this.participantTwoPhaseCommit();
        }
    }

    private void checkForTransactionExtraIfNecessary()
            throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
            IllegalStateException, CommitRequiredException, SystemException {

        if (this.transactionalExtra != null) /* for ByteTCC */ {
            if (this.participantList.isEmpty() == false
                    && this.participant == null) /* see initGetTransactionStrategy */ {
                this.participantRollback();
                throw new HeuristicRollbackException();
            } else if (this.participantList.size() > 1) {
                this.participantRollback();
                throw new HeuristicRollbackException();
            }
        } // end-if (this.transactionalExtra != null)

    }

    private void participantOnePhaseCommit()
            throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
            IllegalStateException, CommitRequiredException, SystemException {

        this.checkForTransactionExtraIfNecessary();

        if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            this.participantRollback();
            throw new HeuristicRollbackException();
        } else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
            throw new HeuristicMixedException();
        } else if (this.transactionStatus == Status.STATUS_ROLLEDBACK) {
            throw new HeuristicRollbackException();
        } else if (this.transactionStatus == Status.STATUS_UNKNOWN) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_NO_TRANSACTION) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_COMMITTED) {
            return;
        } /* else active, preparing, prepared, committing {} */

        try {
            this.synchronizationList.beforeCompletion();
            this.delistAllResource();

            try {
                this.invokeParticipantPrepare();
            } catch (CommitRequiredException crex) {
                // some RMs has already been committed.
            }

            this.invokeParticipantCommit();
        } catch (RollbackRequiredException rrex) {
            this.participantRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(rrex);
            throw hrex;
        } catch (SystemException ex) {
            this.participantRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(ex);
            throw hrex;
        } catch (RuntimeException rex) {
            this.participantRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(rex);
            throw hrex;
        } finally {
            this.synchronizationList.afterCompletion(this.transactionStatus);
        }

    }

    private void participantTwoPhaseCommit()
            throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
            IllegalStateException, CommitRequiredException, SystemException {

        if (this.transactionStatus == Status.STATUS_ACTIVE) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            this.participantRollback();
            throw new HeuristicRollbackException();
        } else if (this.transactionStatus == Status.STATUS_ROLLING_BACK) {
            throw new HeuristicMixedException();
        } else if (this.transactionStatus == Status.STATUS_ROLLEDBACK) {
            throw new HeuristicRollbackException();
        } else if (this.transactionStatus == Status.STATUS_UNKNOWN) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_NO_TRANSACTION) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_COMMITTED) {
            return;
        } /* else preparing, prepared, committing {} */

        try {
            this.synchronizationList.beforeCompletion();
            this.delistAllResource();
            this.invokeParticipantCommit();
        } catch (RollbackRequiredException rrex) {
            this.participantRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(rrex);
            throw hrex;
        } catch (SystemException ex) {
            this.participantRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(ex);
            throw hrex;
        } catch (RuntimeException rex) {
            this.participantRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(rex);
            throw hrex;
        } finally {
            this.synchronizationList.afterCompletion(this.transactionStatus);
        }

    }

    private void invokeParticipantPrepare() throws RollbackRequiredException, CommitRequiredException {
        TransactionXid xid = this.transactionContext.getXid();
        TransactionArchive archive = this.getTransactionArchive();
        TransactionLogger transactionLogger = beanFactory.getTransactionLogger();

        logger.info("[{}] prepare-transaction start", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));

        this.transactionStatus = Status.STATUS_PREPARING;
        archive.setStatus(this.transactionStatus);
        this.transactionListenerList.onPrepareStart(xid);
        transactionLogger.updateTransaction(archive);

        boolean unFinishExists = true;
        try {
            TransactionStrategy currentStrategy = this.getTransactionStrategy();
            currentStrategy.prepare(xid);

            unFinishExists = false;
        } catch (CommitRequiredException ex) {
            unFinishExists = false;
            throw ex;
        } catch (RollbackRequiredException ex) {
            this.transactionListenerList.onPrepareFailure(xid);
            logger.info("[{}] prepare-transaction failed", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));
            throw ex;
        } catch (RuntimeException ex) {
            this.transactionListenerList.onPrepareFailure(xid);
            logger.info("[{}] prepare-transaction failed", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));
            throw ex;
        } finally {
            if (unFinishExists == false) {
                this.transactionStatus = Status.STATUS_PREPARED;
                archive.setStatus(this.transactionStatus);
                this.transactionListenerList.onPrepareSuccess(xid);
                transactionLogger.updateTransaction(archive);

                logger.info("[{}] prepare-transaction complete successfully",
                        ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));
            }
        }

    }

    private void invokeParticipantCommit() throws HeuristicMixedException, HeuristicRollbackException, SystemException {
        TransactionXid xid = this.transactionContext.getXid();
        TransactionArchive archive = this.getTransactionArchive();
        TransactionLogger transactionLogger = beanFactory.getTransactionLogger();

        logger.info("[{}] commit-transaction start", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));

        this.transactionStatus = Status.STATUS_COMMITTING;
        archive.setStatus(this.transactionStatus);
        this.transactionListenerList.onCommitStart(xid);
        transactionLogger.updateTransaction(archive);

        boolean unFinishExists = true;
        try {
            TransactionStrategy currentStrategy = this.getTransactionStrategy();
            currentStrategy.commit(xid);

            unFinishExists = false;
        } catch (HeuristicMixedException ex) {
            this.transactionListenerList.onCommitHeuristicMixed(xid);
            throw ex;
        } catch (HeuristicRollbackException ex) {
            this.transactionListenerList.onCommitHeuristicRolledback(xid);
            throw ex;
        } catch (SystemException ex) {
            this.transactionListenerList.onCommitFailure(xid);
            throw ex;
        } catch (RuntimeException ex) {
            this.transactionListenerList.onCommitFailure(xid);
            throw ex;
        } finally {
            if (unFinishExists == false) {
                this.transactionStatus = Status.STATUS_COMMITTED; // Status.STATUS_COMMITTED;
                archive.setStatus(this.transactionStatus);
                this.transactionListenerList.onCommitSuccess(xid);
                transactionLogger.updateTransaction(archive);

                logger.info("[{}] commit-transaction complete successfully",
                        ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));
            }
        }
    }

    public synchronized void commit()
            throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
            IllegalStateException, CommitRequiredException, SystemException {

        if (this.transactionStatus == Status.STATUS_ACTIVE) {
            this.fireCommit();
        } else if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            this.fireRollback();
            throw new HeuristicRollbackException();
        } else if (this.transactionStatus == Status.STATUS_ROLLEDBACK) /* should never happen */ {
            throw new RollbackException();
        } else if (this.transactionStatus == Status.STATUS_COMMITTED) /* should never happen */ {
            logger.debug("Current transaction has already been committed.");
        } else {
            throw new IllegalStateException();
        }

    }

    private void fireCommit()
            throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException,
            IllegalStateException, CommitRequiredException, SystemException {

        // stop-timing
        beanFactory.getTransactionTimer().stopTiming(this);

        // before-completion
        this.synchronizationList.beforeCompletion();

        // delist all resources
        try {
            this.delistAllResource();
        } catch (RollbackRequiredException rrex) {
            this.fireRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(rrex);
            throw hrex;
        } catch (SystemException ex) {
            this.fireRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(ex);
            throw hrex;
        } catch (RuntimeException rex) {
            this.fireRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(rex);
            throw hrex;
        }

        try {
            TransactionXid xid = this.transactionContext.getXid();
            logger.info("[{}] commit-transaction start", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));

            if (this.participantList.size() == 0) {
                this.skipOnePhaseCommit();
            } else if (this.participantList.size() == 1) {
                this.fireOnePhaseCommit();
            } else {
                this.fireTwoPhaseCommit();
            }

            logger.info("[{}] commit-transaction complete successfully",
                    ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));
        } finally {
            this.synchronizationList.afterCompletion(this.transactionStatus);
        }
    }

    public synchronized void skipOnePhaseCommit()
            throws HeuristicRollbackException, HeuristicMixedException, CommitRequiredException, SystemException {
        TransactionXid xid = this.transactionContext.getXid();
        this.transactionListenerList.onCommitStart(xid);
        this.transactionListenerList.onCommitSuccess(xid);
    }

    public synchronized void fireOnePhaseCommit()
            throws HeuristicRollbackException, HeuristicMixedException, CommitRequiredException, SystemException {

        XAResourceArchive archive = null;
        if (this.nativeParticipantList.size() > 0) {
            archive = this.nativeParticipantList.get(0);
        } else if (this.remoteParticipantList.size() > 0) {
            archive = this.remoteParticipantList.get(0);
        } else {
            archive = this.participant;
        }

        TransactionXid xid = this.transactionContext.getXid();
        try {
            this.transactionListenerList.onCommitStart(xid);
            archive.commit(xid, true);
            this.transactionListenerList.onCommitSuccess(xid);
        } catch (XAException xaex) {
            switch (xaex.errorCode) {
                case XAException.XA_HEURMIX:
                    this.transactionListenerList.onCommitHeuristicMixed(xid);
                    HeuristicMixedException hmex = new HeuristicMixedException();
                    hmex.initCause(xaex);
                    throw hmex;
                case XAException.XA_HEURCOM:
                    this.transactionListenerList.onCommitSuccess(xid);
                    break;
                case XAException.XA_HEURRB:
                    this.transactionListenerList.onCommitHeuristicRolledback(xid);
                    HeuristicRollbackException hrex = new HeuristicRollbackException();
                    hrex.initCause(xaex);
                    throw hrex;
                default:
                    this.transactionListenerList.onCommitFailure(xid);
                    SystemException ex = new SystemException();
                    ex.initCause(xaex);
                    throw ex;
            }
        } catch (RuntimeException rex) {
            this.transactionListenerList.onCommitFailure(xid);
            SystemException sysEx = new SystemException();
            sysEx.initCause(rex);
            throw sysEx;
        }
    }

    public synchronized void fireTwoPhaseCommit()
            throws HeuristicRollbackException, HeuristicMixedException, CommitRequiredException, SystemException {
        TransactionLogger transactionLogger = beanFactory.getTransactionLogger();

        final TransactionStrategy currentStrategy = this.getTransactionStrategy();

        final TransactionXid xid = this.transactionContext.getXid();

        TransactionArchive archive = this.getTransactionArchive();// new TransactionArchive();
        this.transactionStatus = Status.STATUS_PREPARING;// .setStatusPreparing();
        archive.setStatus(this.transactionStatus);
        transactionLogger.createTransaction(archive);

        this.transactionListenerList.onPrepareStart(xid);

        // boolean committed = false;
        int vote = XAResource.XA_RDONLY;
        try {
            //            vote = currentStrategy.prepare(xid);
            vote = XAResource.XA_OK;
        } catch (Exception rex) {
            this.transactionListenerList.onPrepareFailure(xid);
            this.fireRollback();
            HeuristicRollbackException hrex = new HeuristicRollbackException();
            hrex.initCause(rex);
            throw hrex;
        }

        this.transactionListenerList.onPrepareSuccess(xid);

        if (vote == XAResource.XA_RDONLY) {
            this.transactionStatus = Status.STATUS_PREPARED;// .setStatusPrepared();
            this.transactionVote = XAResource.XA_RDONLY;
            archive.setVote(XAResource.XA_RDONLY);
            archive.setStatus(this.transactionStatus);
            this.transactionListenerList.onCommitStart(xid);
            this.transactionListenerList.onCommitSuccess(xid);
            transactionLogger.updateTransaction(archive);
        } else {
            // this.transactionStatus = Status.STATUS_PREPARED;// .setStatusPrepared();

            this.transactionStatus = Status.STATUS_COMMITTING;// .setStatusCommiting();
            this.transactionVote = XAResource.XA_OK;
            archive.setVote(this.transactionVote);
            archive.setStatus(this.transactionStatus);
            this.transactionListenerList.onCommitStart(xid);
            transactionLogger.updateTransaction(archive);

            boolean unFinishExists = true;
            try {
                //异步提交，直接返回结果，优化性能
                currentStrategy.commit(xid);
                unFinishExists = false;
            } catch (RuntimeException ex) {
                this.transactionListenerList.onCommitFailure(xid);
                throw ex;
            } finally {
                if (unFinishExists == false) {
                    this.transactionStatus = Status.STATUS_COMMITTED; // Status.STATUS_COMMITTED;
                    archive.setStatus(this.transactionStatus);
                    this.transactionListenerList.onCommitSuccess(xid);
                    transactionLogger.updateTransaction(archive);
                }
            }
        } // end-else-if (vote == XAResource.XA_RDONLY)

    }

    public synchronized boolean delistResource(XAResource xaRes, int flag)
            throws IllegalStateException, SystemException {
        if (this.transactionStatus != Status.STATUS_ACTIVE && this.transactionStatus != Status.STATUS_MARKED_ROLLBACK) {
            throw new IllegalStateException();
        }

        try {
            if (XAResourceDescriptor.class.isInstance(xaRes)) {
                return this.delistResource((XAResourceDescriptor) xaRes, flag);
            } else {
                XAResourceDescriptor descriptor = new UnidentifiedResourceDescriptor();
                ((UnidentifiedResourceDescriptor) descriptor).setDelegate(xaRes);
                ((UnidentifiedResourceDescriptor) descriptor).setIdentifier("");
                return this.delistResource(descriptor, flag);
            }
        } finally {
            if (flag == XAResource.TMFAIL) {
                this.setRollbackOnlyQuietly();
            }
        }

    }

    public boolean delistResource(XAResourceDescriptor descriptor, int flag)
            throws IllegalStateException, SystemException {
        String identifier = descriptor.getIdentifier();

        RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
        String self = transactionCoordinator.getIdentifier();
        String parent = String.valueOf(this.transactionContext.getPropagatedBy());

        if (StringUtils.equalsIgnoreCase(identifier, self) || CommonUtils.instanceEquals(parent, identifier)) {
            return true;
        }

        XAResourceArchive archive = this.participantMap.get(identifier);
        if (archive == null) {
            throw new SystemException();
        }

        boolean success = false;
        try {
            success = this.delistResource(archive, flag);
        } finally {
            if (success) {
                this.resourceListenerList.onDelistResource(archive.getXid(), descriptor);
            }
        }

        return true;
    }

    private boolean delistResource(XAResourceArchive archive, int flag)
            throws SystemException, RollbackRequiredException {
        try {
            Xid branchXid = archive.getXid();

            switch (flag) {
                case XAResource.TMSUCCESS:
                case XAResource.TMFAIL:
                    //                    archive.end(branchXid, flag);
                    archive.setDelisted(true);
                    break;
                case XAResource.TMSUSPEND:
                    //                    archive.end(branchXid, flag);
                    archive.setDelisted(true);
                    archive.setSuspended(true);
                    break;
                default:
                    throw new SystemException();
            }

            logger.info("[{}] delist: xares= {}, branch= {}, flags= {}",
                    ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), archive,
                    ByteUtils.byteArrayToString(branchXid.getBranchQualifier()), flag);
        } catch (RuntimeException ex) {
            logger.error("XATerminatorImpl.delistResource(XAResourceArchive, int)", ex);

            SystemException sysex = new SystemException();
            sysex.initCause(ex);
            throw sysex;
        }

        return true;
    }

    public synchronized boolean enlistResource(XAResource xaRes)
            throws RollbackException, IllegalStateException, SystemException {

        if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            throw new RollbackException();
        } else if (this.transactionStatus != Status.STATUS_ACTIVE) {
            throw new IllegalStateException();
        }

        if (XAResourceDescriptor.class.isInstance(xaRes)) {
            return this.enlistResource((XAResourceDescriptor) xaRes);
        } else if (XAResourceDescriptor.class.isInstance(xaRes) == false && this.transactionContext.isCoordinator()) {
            XAResourceDescriptor descriptor = new UnidentifiedResourceDescriptor();
            ((UnidentifiedResourceDescriptor) descriptor).setIdentifier("");
            ((UnidentifiedResourceDescriptor) descriptor).setDelegate(xaRes);
            return this.enlistResource(descriptor);
        } else {
            throw new SystemException("Unknown xa resource!");
        }

    }

    public boolean enlistResource(XAResourceDescriptor descriptor)
            throws RollbackException, IllegalStateException, SystemException {
        XAResourceArchive archive = null;

        XAResourceArchive element = this.participantMap.get(descriptor.getIdentifier()); // dubbo: old identifier
        if (element != null) {
            XAResourceDescriptor xard = element.getDescriptor();
            try {
                archive = xard.isSameRM(descriptor) ? element : archive;
            } catch (Exception ex) {
                logger.debug(ex.getMessage(), ex);
            }
        }

        int flags = XAResource.TMNOFLAGS;
        if (archive == null) {
            archive = new XAResourceArchive();
            archive.setDescriptor(descriptor);
            archive.setIdentified(true);
            TransactionXid globalXid = this.transactionContext.getXid();
            XidFactory xidFactory = this.beanFactory.getXidFactory();
            Xid currentBranchXid = xidFactory.createBranchXid(globalXid);
            currentXid.set(currentBranchXid);
            archive.setXid(currentBranchXid);
        } else {
            flags = XAResource.TMJOIN;
        }

        descriptor.setTransactionTimeoutQuietly(this.transactionTimeout);

        if (this.participant != null && (LocalXAResourceDescriptor.class.isInstance(descriptor)
                || UnidentifiedResourceDescriptor.class.isInstance(descriptor))) {
            XAResourceDescriptor lro = this.participant.getDescriptor();
            try {
                if (lro.isSameRM(descriptor) == false) {
                    throw new SystemException(
                            "Only one non-XA resource is allowed to participate in global transaction.");
                }
            } catch (XAException ex) {
                SystemException sysEx = new SystemException();
                sysEx.initCause(ex);
                throw sysEx;
            } catch (RuntimeException ex) {
                throw new IllegalStateException(ex);
            }
        }

        boolean success = false;
        try {
            success = this.enlistResource(archive, flags);
        } finally {
            if (success) {
                String identifier = descriptor.getIdentifier(); // dubbo: new identifier

                boolean resourceValid = true;
                if (CommonResourceDescriptor.class.isInstance(descriptor)) {
                    this.nativeParticipantList.add(archive);
                } else if (RemoteResourceDescriptor.class.isInstance(descriptor)) {
                    RemoteCoordinator transactionCoordinator = this.beanFactory.getTransactionCoordinator();
                    String self = transactionCoordinator.getIdentifier();
                    String parent = String.valueOf(this.transactionContext.getPropagatedBy());

                    resourceValid = StringUtils.equalsIgnoreCase(identifier, self) == false
                            && CommonUtils.instanceEquals(parent, identifier) == false;

                    if (resourceValid) {
                        RemoteResourceDescriptor resourceDescriptor = (RemoteResourceDescriptor) descriptor;
                        RemoteCoordinator remoteCoordinator = resourceDescriptor.getDelegate();

                        this.remoteParticipantList.add(archive);
                        this.applicationMap.put(remoteCoordinator.getApplication(), archive);
                    } else {
                        logger.warn("Endpoint {} can not be its own remote branch!", identifier);
                    }
                } else if (this.participant == null) {
                    // this.participant = this.participant == null ? archive : this.participant;
                    this.participant = archive;
                } else {
                    throw new SystemException("There already has a local-resource exists!");
                }

                if (resourceValid) {
                    this.participantList.add(archive);
                    this.participantMap.put(identifier, archive);

                    this.resourceListenerList.onEnlistResource(archive.getXid(), descriptor);
                } // end-if (resourceValid)
            }
        }

        return true;
    }

    private boolean enlistResource(XAResourceArchive archive, int flag) throws SystemException, RollbackException {
        try {
            Xid branchXid = archive.getXid();
            logger.info("[{}] enlist: xares= {}, branch= {}, flags: {}",
                    ByteUtils.byteArrayToString(branchXid.getGlobalTransactionId()), archive,
                    ByteUtils.byteArrayToString(branchXid.getBranchQualifier()), flag);

            //            openGeneralLog(archive);
            switch (flag) {
                case XAResource.TMNOFLAGS:
                    long expired = this.transactionContext.getExpiredTime();
                    long current = System.currentTimeMillis();
                    long remains = expired - current;
                    int timeout = (int) (remains / 1000L);
                    archive.setTransactionTimeout(timeout);
                    archive.start(branchXid, flag);
                    return true;
                case XAResource.TMJOIN:
                    archive.start(branchXid, flag);
                    archive.setDelisted(false);
                    return false;
                case XAResource.TMRESUME:
                    archive.start(branchXid, flag);
                    archive.setDelisted(false);
                    archive.setSuspended(false);
                    return false;
                default:
                    throw new SystemException();
            }
        } catch (XAException xae) {
            logger.error("XATerminatorImpl.enlistResource(XAResourceArchive, int)", xae);

            // Possible exceptions are XA_RB*, XAER_RMERR, XAER_RMFAIL,
            // XAER_DUPID, XAER_OUTSIDE, XAER_NOTA, XAER_INVAL, or XAER_PROTO.
            switch (xae.errorCode) {
                case XAException.XAER_DUPID:
                    // * If neither TMJOIN nor TMRESUME is specified and the transaction
                    // * specified by xid has previously been seen by the resource manager,
                    // * the resource manager throws the XAException exception with XAER_DUPID error code.
                    return false;
                case XAException.XAER_OUTSIDE:
                    // The resource manager is doing work outside any global transaction
                    // on behalf of the application.
                case XAException.XAER_NOTA:
                    // Either TMRESUME or TMJOIN was set inflags, and the specified XID is not
                    // known by the resource manager.
                case XAException.XAER_INVAL:
                    // Invalid arguments were specified.
                case XAException.XAER_PROTO:
                    // The routine was invoked in an improper context.
                    return false;
                case XAException.XAER_RMFAIL:
                    // An error occurred that makes the resource manager unavailable
                case XAException.XAER_RMERR:
                    // An error occurred in associating the transaction branch with the thread of control
                    SystemException sysex = new SystemException();
                    sysex.initCause(xae);
                    throw sysex;
                default:
                    // XA_RB*
                    throw new RollbackException();
            }
        } catch (RuntimeException ex) {
            logger.error("XATerminatorImpl.enlistResource(XAResourceArchive, int)", ex);

            throw new RollbackException();
        }

    }

    public int getStatus() /* throws SystemException */ {
        return this.transactionStatus;
    }

    public synchronized void registerSynchronization(Synchronization sync)
            throws RollbackException, IllegalStateException, SystemException {

        if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            throw new RollbackException();
        } else if (this.transactionStatus == Status.STATUS_ACTIVE) {
            this.synchronizationList.registerSynchronizationQuietly(sync);
            logger.debug("[{}] register-sync: sync= {}"//
                    , ByteUtils.byteArrayToString(this.transactionContext.getXid().getGlobalTransactionId()), sync);
        } else {
            throw new IllegalStateException();
        }

    }

    public synchronized void rollback() throws IllegalStateException, RollbackRequiredException, SystemException {
        if (this.transactionStatus == Status.STATUS_UNKNOWN) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_NO_TRANSACTION) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_COMMITTED) /* should never happen */ {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_ROLLEDBACK) /* should never happen */ {
            logger.debug("Current transaction has already been rolled back.");
        } else {
            this.fireRollback();
        }
    }

    private void fireRollback() throws IllegalStateException, RollbackRequiredException, SystemException {
        beanFactory.getTransactionTimer().stopTiming(this);

        try {
            this.synchronizationList.beforeCompletion();

            this.delistAllResourceQuietly();

            this.invokeParticipantRollback();
        } finally {
            this.synchronizationList.afterCompletion(this.transactionStatus);
        }

    }

    public synchronized void recoveryRollback() throws RollbackRequiredException, SystemException {
        this.recoverIfNecessary(); // Recover if transaction is recovered from tx-log.

        this.invokeParticipantRollback();
    }

    public synchronized void participantRollback()
            throws IllegalStateException, RollbackRequiredException, SystemException {

        if (this.transactionStatus == Status.STATUS_UNKNOWN) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_NO_TRANSACTION) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_COMMITTED) {
            throw new IllegalStateException();
        } else if (this.transactionStatus == Status.STATUS_ROLLEDBACK) {
            return;
        }

        if (this.transactionContext.isRecoveried()) {
            this.recover(); // Execute recoveryInit if transaction is recovered from tx-log.
            this.invokeParticipantRollback();
        } else {
            try {
                this.synchronizationList.beforeCompletion();
                this.delistAllResourceQuietly();
                this.invokeParticipantRollback();
            } finally {
                this.synchronizationList.afterCompletion(this.transactionStatus);
            }
        }

    }

    private void invokeParticipantRollback() throws SystemException {
        TransactionLogger transactionLogger = beanFactory.getTransactionLogger();
        TransactionXid xid = this.transactionContext.getXid();

        logger.info("[{}] rollback-transaction start", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));

        this.transactionStatus = Status.STATUS_ROLLING_BACK;
        TransactionArchive archive = this.getTransactionArchive();
        archive.setStatus(this.transactionStatus);
        this.transactionListenerList.onRollbackStart(xid);
        transactionLogger.updateTransaction(archive); // don't create!

        boolean unFinishExists = true;
        try {
            TransactionStrategy currentStrategy = this.getTransactionStrategy();
            currentStrategy.rollback(xid);

            unFinishExists = false;
        } catch (HeuristicMixedException ex) {
            this.transactionListenerList.onRollbackFailure(xid);
            SystemException sysEx = new SystemException();
            sysEx.initCause(ex);
            throw sysEx;
        } catch (HeuristicCommitException ex) {
            this.transactionListenerList.onRollbackFailure(xid);
            SystemException sysEx = new SystemException();
            sysEx.initCause(ex);
            throw sysEx;
        } catch (SystemException ex) {
            this.transactionListenerList.onRollbackFailure(xid);
            throw ex;
        } catch (RuntimeException ex) {
            this.transactionListenerList.onRollbackFailure(xid);
            throw ex;
        } finally {
            if (unFinishExists == false) {
                this.transactionStatus = Status.STATUS_ROLLEDBACK; // Status.STATUS_ROLLEDBACK;
                archive.setStatus(this.transactionStatus);
                this.transactionListenerList.onRollbackSuccess(xid);
                transactionLogger.updateTransaction(archive);

                logger.info("[{}] rollback-transaction complete successfully",
                        ByteUtils.byteArrayToString(xid.getGlobalTransactionId()));
            }
        }
    }

    public void suspend() throws RollbackRequiredException, SystemException {
        boolean rollbackRequired = false;
        boolean errorExists = false;

        for (int i = 0; i < this.participantList.size(); i++) {
            XAResourceArchive xares = this.participantList.get(i);
            if (xares.isDelisted() == false) {
                try {
                    this.delistResource(xares, XAResource.TMSUSPEND);
                } catch (RollbackRequiredException ex) {
                    rollbackRequired = true;
                } catch (SystemException ex) {
                    errorExists = true;
                } catch (RuntimeException ex) {
                    errorExists = true;
                }
            }
        }

        if (rollbackRequired) {
            this.setRollbackOnlyQuietly();
            throw new RollbackRequiredException();
        } else if (errorExists) {
            throw new SystemException(XAException.XAER_RMERR);
        }

    }

    public void resume() throws RollbackRequiredException, SystemException {
        boolean rollbackRequired = false;
        boolean errorExists = false;
        for (int i = 0; i < this.participantList.size(); i++) {
            XAResourceArchive xares = this.participantList.get(i);
            if (xares.isDelisted()) {
                try {
                    this.enlistResource(xares, XAResource.TMRESUME);
                } catch (RollbackException rex) {
                    rollbackRequired = true;
                } catch (SystemException rex) {
                    errorExists = true;
                } catch (RuntimeException rex) {
                    errorExists = true;
                }
            }
        }

        if (rollbackRequired) {
            this.setRollbackOnlyQuietly();
            throw new RollbackRequiredException();
        } else if (errorExists) {
            throw new SystemException(XAException.XAER_RMERR);
        }

    }

    private void delistAllResourceQuietly() {
        try {
            this.delistAllResource();
        } catch (RollbackRequiredException rrex) {
            logger.warn(rrex.getMessage(), rrex);
        } catch (SystemException ex) {
            logger.warn(ex.getMessage(), ex);
        } catch (RuntimeException rex) {
            logger.warn(rex.getMessage(), rex);
        }
    }

    private void delistAllResource() throws RollbackRequiredException, SystemException {
        boolean rollbackRequired = false;
        boolean errorExists = false;
        for (int i = 0; i < this.participantList.size(); i++) {
            XAResourceArchive xares = this.participantList.get(i);
            if (xares.isDelisted() == false) {
                try {
                    this.delistResource(xares, XAResource.TMSUCCESS);
                } catch (RollbackRequiredException ex) {
                    rollbackRequired = true;
                } catch (SystemException ex) {
                    errorExists = true;
                } catch (RuntimeException ex) {
                    errorExists = true;
                } finally {
                    this.resourceListenerList.onDelistResource(xares.getXid(), xares.getDescriptor());
                }
            }
        } // end-for

        if (rollbackRequired) {
            throw new RollbackRequiredException();
        } else if (errorExists) {
            throw new SystemException(XAException.XAER_RMERR);
        }
    }

    public void setRollbackOnlyQuietly() {
        try {
            this.setRollbackOnly();
        } catch (Exception ex) {
            logger.debug(ex.getMessage(), ex);
        }
    }

    public synchronized void setRollbackOnly() throws IllegalStateException, SystemException {
        if (this.transactionStatus == Status.STATUS_ACTIVE || this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            this.transactionStatus = Status.STATUS_MARKED_ROLLBACK;
        } else {
            throw new IllegalStateException();
        }
    }

    public void recoverIfNecessary() throws SystemException {
        if (this.transactionContext.isRecoveried()) {
            this.recover();
        }
    }

    public synchronized void recover() throws SystemException {
        if (transactionStatus == Status.STATUS_PREPARING) {
            this.recover4PreparingStatus();
        } else if (transactionStatus == Status.STATUS_COMMITTING) {
            this.recover4CommittingStatus();
        } else if (transactionStatus == Status.STATUS_ROLLING_BACK) {
            this.recover4RollingBackStatus();
        }
    }

    public void recover4PreparingStatus() throws SystemException {
        TransactionLogger transactionLogger = this.beanFactory.getTransactionLogger();

        boolean unPrepareExists = false;
        for (int i = 0; i < this.participantList.size(); i++) {
            XAResourceArchive archive = this.participantList.get(i);

            boolean prepareFlag = archive.getVote() != XAResourceArchive.DEFAULT_VOTE;
            boolean preparedVal = archive.isReadonly() || prepareFlag;

            if (archive.isRecovered()) {
                unPrepareExists = preparedVal ? unPrepareExists : true;
                continue;
            } else if (preparedVal) {
                continue;
            }

            boolean xidExists = this.recover(archive);
            unPrepareExists = xidExists ? false : true;
        }

        if (unPrepareExists == false) {
            this.transactionStatus = Status.STATUS_PREPARED;

            TransactionArchive archive = this.getTransactionArchive();
            transactionLogger.updateTransaction(archive);
        }

    }

    public void recover4CommittingStatus() throws SystemException {
        TransactionLogger transactionLogger = this.beanFactory.getTransactionLogger();

        boolean rollbackExists = false;
        boolean unCommitExists = false;
        for (int i = 0; i < this.participantList.size(); i++) {
            XAResourceArchive archive = this.participantList.get(i);

            XAResourceDescriptor descriptor = archive.getDescriptor();
            XAResource delegate = descriptor.getDelegate();
            boolean localFlag = LocalXAResource.class.isInstance(delegate);

            if (localFlag //
                    && LastResourceOptimizeStrategy.class.isInstance(this.transactionStrategy)) {
                throw new SystemException();
            }

            if (archive.isRecovered()) {
                unCommitExists = archive.isCommitted() ? unCommitExists : true;
                continue;
            } else if (archive.isCommitted()) {
                continue;
            }

            boolean xidExists = this.recover(archive);
            if (localFlag) {
                rollbackExists = xidExists ? rollbackExists : true;
            } else {
                unCommitExists = xidExists ? true : unCommitExists;
            }

        }

        if (rollbackExists) {
            this.transactionStatus = Status.STATUS_ROLLING_BACK;

            TransactionArchive archive = this.getTransactionArchive();
            transactionLogger.updateTransaction(archive);
        } else if (unCommitExists == false) {
            this.transactionStatus = Status.STATUS_COMMITTED;

            TransactionArchive archive = this.getTransactionArchive();
            transactionLogger.updateTransaction(archive);
        }

    }

    public void recover4RollingBackStatus() throws SystemException {
        TransactionLogger transactionLogger = this.beanFactory.getTransactionLogger();

        boolean unRollbackExists = false;
        for (int i = 0; i < this.participantList.size(); i++) {
            XAResourceArchive archive = this.participantList.get(i);

            if (archive.isRecovered()) {
                unRollbackExists = archive.isRolledback() ? unRollbackExists : true;
                continue;
            } else if (archive.isRolledback()) {
                continue;
            }

            boolean xidExists = false;
            try {
                xidExists = this.recover(archive);
            } catch (SystemException e) {
                this.forgetQuietly();
            }
            unRollbackExists = xidExists ? true : unRollbackExists;
        }

        if (unRollbackExists == false) {
            this.transactionStatus = Status.STATUS_ROLLEDBACK;

            TransactionArchive archive = this.getTransactionArchive();
            transactionLogger.updateTransaction(archive);
        }

    }

    private boolean recover(XAResourceArchive archive) throws SystemException {
        TransactionXid globalXid = this.transactionContext.getXid();

        boolean xidRecovered = false;

        XAResourceDescriptor descriptor = archive.getDescriptor();
        XAResource delegate = descriptor.getDelegate();
        boolean nativeFlag = LocalXAResource.class.isInstance(delegate);
        boolean remoteFlag = RemoteCoordinator.class.isInstance(delegate);
        if (nativeFlag) {
            try {
                ((LocalXAResource) delegate).recoverable(archive.getXid());
                xidRecovered = true;
            } catch (XAException ex) {
                switch (ex.errorCode) {
                    case XAException.XAER_NOTA:
                        break;
                    default:
                        logger.error("[{}] recover-resource failed. branch= {}",
                                ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId()),
                                ByteUtils.byteArrayToString(globalXid.getBranchQualifier()), ex);
                        throw new SystemException();
                }
            }
        } else if (archive.isIdentified()) {
            Xid thisXid = archive.getXid();
            byte[] thisGlobalTransactionId = thisXid.getGlobalTransactionId();
            byte[] thisBranchQualifier = thisXid.getBranchQualifier();
            try {
                Xid[] array = archive.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
                for (int j = 0; xidRecovered == false && array != null && j < array.length; j++) {
                    Xid thatXid = array[j];
                    byte[] thatGlobalTransactionId = thatXid.getGlobalTransactionId();
                    byte[] thatBranchQualifier = thatXid.getBranchQualifier();
                    boolean formatIdEquals = thisXid.getFormatId() == thatXid.getFormatId();
                    boolean transactionIdEquals = Arrays.equals(thisGlobalTransactionId, thatGlobalTransactionId);
                    boolean qualifierEquals = Arrays.equals(thisBranchQualifier, thatBranchQualifier);
                    xidRecovered = formatIdEquals && transactionIdEquals && (remoteFlag || qualifierEquals);
                }
            } catch (Exception ex) {
                logger.error("[{}] recover-resource failed. branch= {}",
                        ByteUtils.byteArrayToString(globalXid.getGlobalTransactionId()),
                        ByteUtils.byteArrayToString(globalXid.getBranchQualifier()), ex);
                throw new SystemException();
            }
        }

        archive.setRecovered(true);

        return xidRecovered;
    }

    public synchronized void forgetQuietly() {
        TransactionXid xid = this.transactionContext.getXid();
        try {
            this.forget();
        } catch (SystemException ex) {
            logger.error("Error occurred while forgetting transaction: {}",
                    ByteUtils.byteArrayToInt(xid.getGlobalTransactionId()), ex);
        } catch (RuntimeException ex) {
            logger.error("Error occurred while forgetting transaction: {}",
                    ByteUtils.byteArrayToInt(xid.getGlobalTransactionId()), ex);
        }
    }

    public synchronized void forget() throws SystemException {
        TransactionRepository repository = beanFactory.getTransactionRepository();
        TransactionLogger transactionLogger = this.beanFactory.getTransactionLogger();

        TransactionXid xid = this.transactionContext.getXid();

        this.cleanup(); // forget branch-transaction has been hueristic completed.

        repository.removeErrorTransaction(xid);
        repository.removeTransaction(xid);

        transactionLogger.deleteTransaction(this.getTransactionArchive());
    }

    public synchronized void cleanup() throws SystemException {
        boolean unFinishExists = false;

        for (int i = 0; i < this.participantList.size(); i++) {
            XAResourceArchive archive = this.participantList.get(i);
            Xid currentXid = archive.getXid();
            if (archive.isHeuristic()) {
                try {
                    Xid branchXid = archive.getXid();
                    archive.forget(branchXid);
                } catch (XAException xae) {
                    // Possible exception values are XAER_RMERR, XAER_RMFAIL
                    // , XAER_NOTA, XAER_INVAL, or XAER_PROTO.
                    switch (xae.errorCode) {
                        case XAException.XAER_RMERR:
                            unFinishExists = true;
                            logger.error("[{}] forget: xares= {}, branch={}, error= {}",
                                    ByteUtils.byteArrayToString(currentXid.getGlobalTransactionId()), archive,
                                    ByteUtils.byteArrayToString(currentXid.getBranchQualifier()), xae.errorCode);
                            break;
                        case XAException.XAER_RMFAIL:
                            unFinishExists = true;
                            logger.error("[{}] forget: xares= {}, branch={}, error= {}",
                                    ByteUtils.byteArrayToString(currentXid.getGlobalTransactionId()), archive,
                                    ByteUtils.byteArrayToString(currentXid.getBranchQualifier()), xae.errorCode);
                            break;
                        case XAException.XAER_NOTA:
                        case XAException.XAER_INVAL:
                        case XAException.XAER_PROTO:
                            break;
                        default:
                            unFinishExists = true;
                            logger.error("[{}] forget: xares= {}, branch={}, error= {}",
                                    ByteUtils.byteArrayToString(currentXid.getGlobalTransactionId()), archive,
                                    ByteUtils.byteArrayToString(currentXid.getBranchQualifier()), xae.errorCode);
                    }
                }
            } // end-if
        } // end-for

        if (unFinishExists) {
            throw new SystemException("Error occurred while cleaning branch transaction!");
        }

    }

    public TransactionArchive getTransactionArchive() {
        TransactionArchive transactionArchive = new TransactionArchive();
        transactionArchive.setVote(this.transactionVote);
        transactionArchive.setXid(this.transactionContext.getXid());
        transactionArchive.setCoordinator(this.transactionContext.isCoordinator());
        transactionArchive.setOptimizedResource(this.participant);
        transactionArchive.getNativeResources().addAll(this.nativeParticipantList);
        transactionArchive.getRemoteResources().addAll(this.remoteParticipantList);
        transactionArchive.setStatus(this.transactionStatus);
        // transactionArchive.setPropagated(this.transactionContext.isPropagated());
        transactionArchive.setPropagatedBy(this.transactionContext.getPropagatedBy());

        TransactionStrategy currentStrategy = this.getTransactionStrategy();
        if (CommonTransactionStrategy.class.isInstance(currentStrategy)) {
            transactionArchive.setTransactionStrategyType(TransactionStrategy.TRANSACTION_STRATEGY_COMMON);
        } else if (SimpleTransactionStrategy.class.isInstance(currentStrategy)) {
            transactionArchive.setTransactionStrategyType(TransactionStrategy.TRANSACTION_STRATEGY_SIMPLE);
        } else if (LastResourceOptimizeStrategy.class.isInstance(currentStrategy)) {
            transactionArchive.setTransactionStrategyType(TransactionStrategy.TRANSACTION_STRATEGY_LRO);
        } else {
            transactionArchive.setTransactionStrategyType(TransactionStrategy.TRANSACTION_STRATEGY_VACANT);
        }

        return transactionArchive;
    }

    public int hashCode() {
        TransactionXid transactionXid = this.transactionContext == null ? null : this.transactionContext.getXid();
        int hash = transactionXid == null ? 0 : transactionXid.hashCode();
        return hash;
    }

    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        } else if (TransactionImpl.class.equals(obj.getClass()) == false) {
            return false;
        }
        TransactionImpl that = (TransactionImpl) obj;
        TransactionContext thisContext = this.transactionContext;
        TransactionContext thatContext = that.transactionContext;
        TransactionXid thisXid = thisContext == null ? null : thisContext.getXid();
        TransactionXid thatXid = thatContext == null ? null : thatContext.getXid();
        return CommonUtils.equals(thisXid, thatXid);
    }

    public void registerTransactionListener(TransactionListener listener) {
        this.transactionListenerList.registerTransactionListener(listener);
    }

    public void registerTransactionResourceListener(TransactionResourceListener listener) {
        this.resourceListenerList.registerTransactionResourceListener(listener);
    }

    public synchronized void stopTiming() {
        this.setTiming(false);
    }

    public synchronized void changeTransactionTimeout(int timeout) {
        long created = this.transactionContext.getCreatedTime();
        transactionContext.setExpiredTime(created + timeout);
    }

    public TransactionStrategy getTransactionStrategy() {
        if (this.transactionStrategy == null) {
            this.transactionStrategy = this.initGetTransactionStrategy();
        }
        return this.transactionStrategy;
    }

    private TransactionStrategy initGetTransactionStrategy() {
        int nativeResNum = this.nativeParticipantList.size();
        int remoteResNum = this.remoteParticipantList.size();

        TransactionStrategy transactionStrategy = null;
        if (this.participantList.isEmpty()) {
            transactionStrategy = new VacantTransactionStrategy();
        } else if (this.participant == null) /* TODO: LRO */ {
            XATerminatorImpl nativeTerminator = new XATerminatorImpl();
            nativeTerminator.setBeanFactory(this.beanFactory);
            nativeTerminator.getResourceArchives().addAll(this.nativeParticipantList);

            XATerminatorImpl remoteTerminator = new XATerminatorImpl();
            remoteTerminator.setBeanFactory(this.beanFactory);
            remoteTerminator.getResourceArchives().addAll(this.remoteParticipantList);

            if (nativeResNum == 0) {
                transactionStrategy = new SimpleTransactionStrategy(remoteTerminator);
            } else if (remoteResNum == 0) {
                transactionStrategy = new SimpleTransactionStrategy(nativeTerminator);
            } else {
                transactionStrategy = new CommonTransactionStrategy(nativeTerminator, remoteTerminator);
            }

        } else {
            XATerminatorOptd terminatorOne = new XATerminatorOptd();
            terminatorOne.setBeanFactory(this.beanFactory);
            terminatorOne.getResourceArchives().add(this.participant);

            XATerminatorImpl terminatorTwo = new XATerminatorImpl();
            terminatorTwo.setBeanFactory(this.beanFactory);
            terminatorTwo.getResourceArchives().addAll(this.nativeParticipantList);
            terminatorTwo.getResourceArchives().addAll(this.remoteParticipantList);

            int resNumber = nativeResNum + remoteResNum;
            if (resNumber == 0) {
                transactionStrategy = new SimpleTransactionStrategy(terminatorOne);
            } else {
                transactionStrategy = new LastResourceOptimizeStrategy(terminatorOne, terminatorTwo);
            }
        }

        return transactionStrategy;
    }

    public void recoverTransactionStrategy(int transactionStrategyType) {
        int nativeResNum = this.nativeParticipantList.size();
        int remoteResNum = this.remoteParticipantList.size();

        XATerminatorImpl nativeTerminator = new XATerminatorImpl();
        nativeTerminator.setBeanFactory(this.beanFactory);
        nativeTerminator.getResourceArchives().addAll(this.nativeParticipantList);

        XATerminatorImpl remoteTerminator = new XATerminatorImpl();
        remoteTerminator.setBeanFactory(this.beanFactory);
        remoteTerminator.getResourceArchives().addAll(this.remoteParticipantList);

        if (TransactionStrategy.TRANSACTION_STRATEGY_COMMON == transactionStrategyType) {
            if (this.participant != null) {
                throw new IllegalStateException();
            } else if (nativeResNum == 0 || remoteResNum == 0) {
                throw new IllegalStateException();
            }
            this.transactionStrategy = new CommonTransactionStrategy(nativeTerminator, remoteTerminator);
        } else if (TransactionStrategy.TRANSACTION_STRATEGY_SIMPLE == transactionStrategyType) {
            if (this.participant == null) {
                if (nativeResNum > 0 && remoteResNum > 0) {
                    throw new IllegalStateException();
                } else if (nativeResNum == 0 && remoteResNum == 0) {
                    throw new IllegalStateException();
                } else if (nativeResNum == 0) {
                    this.transactionStrategy = new SimpleTransactionStrategy(remoteTerminator);
                } else {
                    this.transactionStrategy = new SimpleTransactionStrategy(nativeTerminator);
                }
            } else {
                int resNumber = nativeResNum + remoteResNum;
                if (resNumber > 0) {
                    throw new IllegalStateException();
                }
                XATerminatorOptd terminatorOne = new XATerminatorOptd();
                terminatorOne.setBeanFactory(this.beanFactory);
                terminatorOne.getResourceArchives().add(this.participant);
                this.transactionStrategy = new SimpleTransactionStrategy(terminatorOne);
            }
        } else if (TransactionStrategy.TRANSACTION_STRATEGY_LRO == transactionStrategyType) {
            if (this.participant == null) {
                throw new IllegalStateException();
            }
            XATerminatorOptd terminatorOne = new XATerminatorOptd();
            terminatorOne.setBeanFactory(this.beanFactory);
            terminatorOne.getResourceArchives().add(this.participant);

            XATerminatorImpl terminatorTwo = new XATerminatorImpl();
            terminatorTwo.setBeanFactory(this.beanFactory);
            terminatorTwo.getResourceArchives().addAll(this.nativeParticipantList);
            terminatorTwo.getResourceArchives().addAll(this.remoteParticipantList);

            this.transactionStrategy = new LastResourceOptimizeStrategy(terminatorOne, terminatorTwo);
        } else {
            if (this.participant != null || nativeResNum > 0 || remoteResNum > 0) {
                throw new IllegalStateException();
            }
            this.transactionStrategy = new VacantTransactionStrategy();
        }

    }

    public void setTransactionStrategy(TransactionStrategy transactionStrategy) {
        this.transactionStrategy = transactionStrategy;
    }

    public Object getTransactionalExtra() {
        return transactionalExtra;
    }

    public void setTransactionalExtra(Object transactionalExtra) {
        this.transactionalExtra = transactionalExtra;
    }

    public TransactionContext getTransactionContext() {
        return transactionContext;
    }

    public boolean isTiming() {
        return timing;
    }

    public void setTiming(boolean timing) {
        this.timing = timing;
    }

    public int getTransactionStatus() {
        return transactionStatus;
    }

    public void setTransactionStatus(int transactionStatus) {
        this.transactionStatus = transactionStatus;
    }

    public int getTransactionTimeout() {
        return transactionTimeout;
    }

    public void setTransactionTimeout(int transactionTimeout) {
        this.transactionTimeout = transactionTimeout;
    }

    public XAResourceArchive getParticipant() {
        return participant;
    }

    public Map<String, XAResourceArchive> getApplicationMap() {
        return applicationMap;
    }

    public Map<String, XAResourceArchive> getParticipantMap() {
        return participantMap;
    }

    public List<XAResourceArchive> getParticipantList() {
        return participantList;
    }

    public void setParticipant(XAResourceArchive participant) {
        this.participant = participant;
    }

    public List<XAResourceArchive> getNativeParticipantList() {
        return nativeParticipantList;
    }

    public List<XAResourceArchive> getRemoteParticipantList() {
        return remoteParticipantList;
    }

    public synchronized int participantStart() throws SystemException {

        TransactionXid xid = this.transactionContext.getXid();

        TransactionArchive archive = this.getTransactionArchive();

        TransactionLogger transactionLogger = beanFactory.getTransactionLogger();
        this.transactionStatus = Status.STATUS_ACTIVE;
        archive.setStatus(this.transactionStatus);
        transactionLogger.createTransaction(archive);

        if (this.transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            // this.transactionListenerList.onPrepareSuccess(xid);
            throw new RollbackRequiredException();
        }

        try {
            TransactionStrategy currentStrategy = this.getTransactionStrategy();
            currentStrategy.start(xid);
            this.transactionStatus = Status.STATUS_PREPARED;
            archive.setStatus(this.transactionStatus);
            return 0;
        } catch (CommitRequiredException crex) {
            this.transactionVote = XAResource.XA_OK;
            archive.setVote(this.transactionVote);

            this.transactionStatus = Status.STATUS_COMMITTING;
            archive.setStatus(this.transactionStatus);

            this.transactionListenerList.onPrepareSuccess(xid);
            throw crex;
        } catch (RollbackRequiredException rrex) {
            this.transactionStatus = Status.STATUS_ROLLING_BACK;
            archive.setStatus(this.transactionStatus);

            this.transactionListenerList.onPrepareFailure(xid);
            throw rrex;
        } catch (RuntimeException xaex) {
            this.transactionStatus = Status.STATUS_ROLLING_BACK;
            archive.setStatus(this.transactionStatus);

            this.transactionListenerList.onPrepareFailure(xid);
            RollbackRequiredException rrex = new RollbackRequiredException();
            rrex.initCause(xaex);
            throw rrex;
        } finally {
            transactionLogger.updateTransaction(archive);
        }
    }

}
