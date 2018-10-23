/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.feisoft.jta.strategy;

import org.feisoft.jta.TransactionStrategy;
import org.feisoft.transaction.CommitRequiredException;
import org.feisoft.transaction.RollbackRequiredException;
import org.feisoft.transaction.resource.XATerminator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.HeuristicCommitException;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

public class SimpleTransactionStrategy implements TransactionStrategy {
	static final Logger logger = LoggerFactory.getLogger(SimpleTransactionStrategy.class);

	private final XATerminator terminator;

	public SimpleTransactionStrategy(XATerminator terminator) {
		if (terminator == null || terminator.getResourceArchives().isEmpty()) {
			throw new IllegalStateException();
		}

		this.terminator = terminator;
	}

	public int start(Xid xid) throws RollbackRequiredException, CommitRequiredException {
		try {
			this.terminator.start(xid,1);
			return 0;
		} catch (XAException xaex) {
			throw new RollbackRequiredException();
		} catch (RuntimeException xaex) {
			throw new RollbackRequiredException();
		}
	}

	public int prepare(Xid xid) throws XAException {
		throw  new XAException("IllegalOperation= prapare");

	}

	public void commit(Xid xid)
			throws HeuristicMixedException, HeuristicRollbackException, IllegalStateException, SystemException {
		try {
			this.terminator.commit(xid, false);
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XA_HEURCOM:
				break;
			case XAException.XA_HEURMIX:
				throw new HeuristicMixedException();
			case XAException.XA_HEURRB:
				throw new HeuristicRollbackException();
			default:
				logger.error("Unknown state in committing transaction phase.", xaex);
				throw new SystemException();
			}
		} catch (RuntimeException rex) {
			logger.error("Unknown state in committing transaction phase.", rex);
			throw new SystemException();
		}
	}

	public void rollback(Xid xid)
			throws HeuristicMixedException, HeuristicCommitException, IllegalStateException, SystemException {
		try {
			this.terminator.rollback(xid);
		} catch (XAException xaex) {
			switch (xaex.errorCode) {
			case XAException.XA_HEURRB:
				break;
			case XAException.XA_HEURMIX:
				throw new HeuristicMixedException();
			case XAException.XA_HEURCOM:
				throw new HeuristicCommitException();
			default:
				logger.error("Unknown state in rollingback transaction phase.", xaex);
				throw new SystemException();
			}
		} catch (RuntimeException rex) {
			logger.error("Unknown state in rollingback transaction phase.", rex);
			throw new SystemException();
		}
	}

}
