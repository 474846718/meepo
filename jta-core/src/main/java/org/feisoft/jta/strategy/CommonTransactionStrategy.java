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

import javax.transaction.HeuristicCommitException;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

public class CommonTransactionStrategy implements TransactionStrategy {
	private final XATerminator nativeTerminator;
	private final XATerminator remoteTerminator;

	public CommonTransactionStrategy(XATerminator nativeTerminator, XATerminator remoteTerminator) {
		if (nativeTerminator == null || nativeTerminator.getResourceArchives().isEmpty()) {
			throw new IllegalStateException();
		} else if (remoteTerminator == null || remoteTerminator.getResourceArchives().isEmpty()) {
			throw new IllegalStateException();
		}

		this.nativeTerminator = nativeTerminator;
		this.remoteTerminator = remoteTerminator;
	}

	public int start(Xid xid) throws RollbackRequiredException, CommitRequiredException {
		return 0;
	}

	public int prepare(Xid xid) throws XAException {
		throw  new XAException("IllegalOperation= prapare");

	}

	public void commit(Xid xid)
			throws HeuristicMixedException, HeuristicRollbackException, IllegalStateException, SystemException {
		boolean committedExists = false;
		boolean rolledbackExists = false;
		boolean unFinishExists = false;
		boolean errorExists = false;
		try {
			this.nativeTerminator.commit(xid, false);
			committedExists = true;
		} catch (XAException ex) {
			// error: XA_HEURHAZ, XA_HEURMIX, XA_HEURCOM, XA_HEURRB, XA_RDONLY, XAER_RMERR
			switch (ex.errorCode) {
			case XAException.XA_HEURCOM:
				committedExists = true;
				break;
			case XAException.XA_HEURRB:
				rolledbackExists = true;
				break;
			case XAException.XA_HEURMIX:
				committedExists = true;
				rolledbackExists = true;
				break;
			case XAException.XA_HEURHAZ:
				unFinishExists = true;
				break;
			case XAException.XA_RDONLY:
				break;
			case XAException.XAER_RMERR:
				errorExists = true;
				break;
			default:
				// should never happen
				errorExists = true;
			}
		} catch (RuntimeException ex) {
			unFinishExists = true;
		}

		try {
			this.remoteTerminator.commit(xid, false);
			committedExists = true;
		} catch (XAException ex) {
			// error: XA_HEURHAZ, XA_HEURMIX, XA_HEURCOM, XA_HEURRB, XA_RDONLY, XAER_RMERR
			switch (ex.errorCode) {
			case XAException.XA_HEURCOM:
				committedExists = true;
				break;
			case XAException.XA_HEURRB:
				rolledbackExists = true;
				break;
			case XAException.XA_HEURMIX:
				committedExists = true;
				rolledbackExists = true;
				break;
			case XAException.XA_HEURHAZ:
				unFinishExists = true;
				break;
			case XAException.XA_RDONLY:
				break;
			case XAException.XAER_RMERR:
				errorExists = true;
				break;
			default:
				// should never happen
				errorExists = true;
			}

		} catch (RuntimeException ex) {
			unFinishExists = true;
		}

		if (committedExists && rolledbackExists) {
			throw new HeuristicMixedException();
		} else if (unFinishExists) {
			throw new SystemException(); // hazard
		} else if (errorExists) {
			throw new SystemException();
		} else if (rolledbackExists) {
			throw new HeuristicRollbackException();
		}

	}

	public void rollback(Xid xid)
			throws HeuristicMixedException, HeuristicCommitException, IllegalStateException, SystemException {

		boolean committedExists = false;
		boolean rolledbackExists = false;
		boolean unFinishExists = false;
		boolean errorExists = false;
		try {
			this.nativeTerminator.rollback(xid);
			rolledbackExists = true;
		} catch (XAException ex) {
			// error: XA_HEURHAZ, XA_HEURMIX, XA_HEURCOM, XA_HEURRB, XA_RDONLY, XAER_RMERR
			switch (ex.errorCode) {
			case XAException.XA_HEURCOM:
				committedExists = true;
				break;
			case XAException.XA_HEURRB:
				rolledbackExists = true;
				break;
			case XAException.XA_HEURMIX:
				committedExists = true;
				rolledbackExists = true;
				break;
			case XAException.XA_HEURHAZ:
				unFinishExists = true;
				break;
			case XAException.XA_RDONLY:
				break;
			case XAException.XAER_RMERR:
				errorExists = true;
				break;
			default:
				// should never happen
				errorExists = true;
			}
		} catch (RuntimeException ex) {
			unFinishExists = true;
		}

		try {
			this.remoteTerminator.rollback(xid);
			rolledbackExists = true;
		} catch (XAException ex) {
			// error: XA_HEURHAZ, XA_HEURMIX, XA_HEURCOM, XA_HEURRB, XA_RDONLY, XAER_RMERR
			switch (ex.errorCode) {
			case XAException.XA_HEURCOM:
				committedExists = true;
				break;
			case XAException.XA_HEURRB:
				rolledbackExists = true;
				break;
			case XAException.XA_HEURMIX:
				committedExists = true;
				rolledbackExists = true;
				break;
			case XAException.XA_HEURHAZ:
				unFinishExists = true;
				break;
			case XAException.XA_RDONLY:
				break;
			case XAException.XAER_RMERR:
				errorExists = true;
				break;
			default:
				// should never happen
				errorExists = true;
			}
		} catch (RuntimeException ex) {
			unFinishExists = true;
		}

		if (committedExists && rolledbackExists) {
			throw new HeuristicMixedException();
		} else if (unFinishExists) {
			throw new SystemException(); // hazard
		} else if (errorExists) {
			throw new SystemException();
		} else if (committedExists) {
			throw new HeuristicCommitException();
		}

	}

}
