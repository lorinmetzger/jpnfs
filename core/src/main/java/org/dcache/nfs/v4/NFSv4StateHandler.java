/*
 * Copyright (c) 2009 - 2015 Deutsches Elektronen-Synchroton,
 * Member of the Helmholtz Association, (DESY), HAMBURG, GERMANY
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.dcache.nfs.v4;

import java.net.InetSocketAddress;
import java.security.Principal;
import org.dcache.nfs.v4.xdr.stateid4;
import org.dcache.nfs.ChimeraNFSException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.dcache.nfs.status.BadSessionException;
import org.dcache.nfs.status.BadStateidException;
import org.dcache.nfs.status.StaleClientidException;
import org.dcache.nfs.v4.xdr.sessionid4;
import org.dcache.nfs.v4.xdr.verifier4;
import org.dcache.utils.Cache;
import org.dcache.utils.Bytes;
import org.dcache.utils.NopCacheEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkState;

public class NFSv4StateHandler {

    private static final Logger _log = LoggerFactory.getLogger(NFSv4StateHandler.class);

    // mapping between server generated clietid and nfs_client_id, not confirmed yet
    private final Map<Long, NFS4Client> _clientsByServerId = new HashMap<>();

    private final Cache<sessionid4, NFSv41Session> _sessionById;

    /**
     * Client's lease expiration time in milliseconds.
     */
    private final long _leaseTime;

    private boolean _running;

    public NFSv4StateHandler() {
        this(NFSv4Defaults.NFS4_LEASE_TIME);
    }

    NFSv4StateHandler(long leaseTime) {
        _leaseTime = TimeUnit.SECONDS.toMillis(leaseTime);
        _sessionById = new Cache<>("NFSv41 sessions", 5000, Long.MAX_VALUE,
                _leaseTime * 2,
                new DeadSessionCollector(),
                _leaseTime * 4, TimeUnit.MILLISECONDS);

        _running = true;
    }

    public void removeClient(NFS4Client client) {

	synchronized (this) {
	    checkState(_running, "NFS state handler not running");

            client.sessions().forEach(s -> _sessionById.remove(s.id()));
	    _clientsByServerId.remove(client.getId());
	}
        client.tryDispose();
    }

    private synchronized void addClient(NFS4Client newClient) {

        checkState(_running, "NFS state handler not running");
        _clientsByServerId.put(newClient.getId(), newClient);
    }

    public synchronized NFS4Client getClientByID( Long id) throws ChimeraNFSException {

        checkState(_running, "NFS state handler not running");

        NFS4Client client = _clientsByServerId.get(id);
        if(client == null) {
            throw new StaleClientidException("bad client id.");
        }
        return client;
    }

    public synchronized NFS4Client getClientIdByStateId(stateid4 stateId) throws ChimeraNFSException {

        checkState(_running, "NFS state handler not running");

        NFS4Client client = _clientsByServerId.get(Bytes.getLong(stateId.other, 0));
        if (client == null) {
            throw new BadStateidException("no client for stateid.");
        }
        return client;
    }

    public synchronized NFSv41Session getSession( sessionid4 id) {
        checkState(_running, "NFS state handler not running");
       return _sessionById.get(id);
    }

    public synchronized NFSv41Session removeSession(sessionid4 id) throws ChimeraNFSException {
        NFSv41Session session = _sessionById.remove(id);
        if (session == null) {
            throw new BadSessionException("session not found");
        }

        detachSession(session);
        return session;
    }

    public synchronized void addSession(NFSv41Session session) {
        checkState(_running, "NFS state handler not running");
        _sessionById.put(session.id(), session);
    }

    public synchronized NFS4Client clientByOwner(byte[] ownerid) {
	for(NFS4Client client: _clientsByServerId.values()) {
	    if (client.isOwner(ownerid)) {
		return client;
	    }
	}
        return null;
    }

    public void updateClientLeaseTime(stateid4  stateid) throws ChimeraNFSException {

        checkState(_running, "NFS state handler not running");
        NFS4Client client = getClientIdByStateId(stateid);
        NFS4State state = client.state(stateid);

        if( !state.isConfimed() ) {
            throw new BadStateidException("State is not confirmed"  );
        }

        Stateids.checkStateId(state.stateid(), stateid);
        client.updateLeaseTime();
    }

    public synchronized List<NFS4Client> getClients() {
        checkState(_running, "NFS state handler not running");
        return new ArrayList<>(_clientsByServerId.values());
    }

    public NFS4Client createClient(InetSocketAddress clientAddress, InetSocketAddress localAddress,
            byte[] ownerID, verifier4 verifier, Principal principal, boolean callbackNeeded) {
        NFS4Client client = new NFS4Client(clientAddress, localAddress, ownerID, verifier, principal, _leaseTime, callbackNeeded);
        addClient(client);
        return client;
    }

    /**
     * Detach session from the client. Removes client, if there are no sessions
     * associated with client any more.
     *
     * @param session to detach.
     */
    private void detachSession(NFSv41Session session) {
        NFS4Client client = session.getClient();
        client.removeSession(session);

        /*
        * remove client if there is not sessions any more
        */
        if (!client.hasSessions()) {
            removeClient(client);
        }
    }

    private class DeadSessionCollector extends NopCacheEventListener<sessionid4, NFSv41Session> {

        @Override
        public void notifyExpired(Cache<sessionid4, NFSv41Session> cache, NFSv41Session session) {
            _log.info("Removing expired session: {}", session);
            detachSession(session);
        }
    }

    /**
     * Check is the GRACE period expired.
     * @return true, if grace period expired.
     */
    public boolean hasGracePeriodExpired() {
        checkState(_running, "NFS state handler not running");
	/*
	 * As we do not have a persistent storage for state information,
	 * grace period makes no sense as it ends up as a simple delay
	 * before first IO request can be processed.
	 */
	return true;
    }

    /**
     * Shutdown session lease time watchdog thread.
     */
    public synchronized void shutdown() {
        checkState(_running, "NFS state handler not running");
        _running = false;
        _sessionById.shutdown();
    }
}
