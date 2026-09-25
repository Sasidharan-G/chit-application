const { describe, it, before, after, beforeEach } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment, assertSucceeds, assertFails
} = require('@firebase/rules-unit-testing');
const {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection, query, where, getDocs, serverTimestamp
} = require('firebase/firestore');

let env;
const ADMIN = 'admin-uid';
const AG1 = 'agent-1';
const AG2 = 'agent-2';
const OUTSIDER = 'agent-3'; // active agent with no chit assigned
const STRANGER = 'no-role-user'; // signed in, but neither admin nor agent

const db = uid => env.authenticatedContext(uid).firestore();
const anon = () => env.unauthenticatedContext().firestore();

async function seed() {
  await env.withSecurityRulesDisabled(async ctx => {
    const f = ctx.firestore();
    await setDoc(doc(f, 'admins', ADMIN), { role: 'admin' });
    for (const [id, groups, active] of [[AG1, ['G1'], true], [AG2, ['G1'], true], [OUTSIDER, ['G9'], true], ['agent-off', ['G1'], false]]) {
      await setDoc(doc(f, 'agents', id), { name: id, phone: '9' + id.length, isActive: active, assignedGroups: groups });
    }
    await setDoc(doc(f, 'chitGroups', 'G1'), { name: 'Chit 1', registerNo: 'R1', chitValue: 3000000, durationMonths: 3, subscriberCount: 3, branch: 'Main', startDate: '01-Jan-2026', status: 'ACTIVE', agentIds: [AG1, AG2] });
    await setDoc(doc(f, 'chitGroups', 'G9'), { name: 'Chit 9', registerNo: 'R9', status: 'ACTIVE', agentIds: [OUTSIDER] });
    await setDoc(doc(f, 'members', 'M1'), { name: 'Ramesh', phone: '9876543210', isActive: true, agentIds: [AG1, AG2] });
    await setDoc(doc(f, 'chitMemberships', 'M1:G1'), { memberId: 'M1', groupId: 'G1', isActive: true, agentIds: [AG1, AG2] });
    await setDoc(doc(f, 'installments', 'G1-I1'), { groupId: 'G1', installmentNo: 1, baseAmount: 100000, agentIds: [AG1, AG2] });
  });
}

function collectionDoc(over = {}) {
  return {
    agentId: AG1, agentName: 'Agent 1', memberId: 'M1', memberName: 'Ramesh', groupId: 'G1', chitNo: 'R1',
    amountPaise: 100000, mode: 'Cash', receiptNo: 'JVC-1', notes: '', businessDate: '2026-01-05',
    timestamp: serverTimestamp(), status: 'PAID', syncedToAdmin: false, requestId: 'req-1', agentIds: [AG1, AG2],
    ...over
  };
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'jothi-vel-chits',
    firestore: { rules: fs.readFileSync(path.join(__dirname, '..', '..', 'firestore.rules'), 'utf8'), host: '127.0.0.1', port: 8080 }
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); await seed(); });

describe('nobody gets in without a real role', () => {
  it('rejects unauthenticated access to everything', async () => {
    for (const p of [['chitGroups', 'G1'], ['members', 'M1'], ['agents', AG1], ['collections', 'x'], ['admins', ADMIN]]) {
      await assertFails(getDoc(doc(anon(), ...p)));
    }
    await assertFails(getDocs(collection(anon(), 'members')));
  });

  it('rejects a signed-in user who is neither admin nor agent (e.g. a leftover anonymous account)', async () => {
    const f = db(STRANGER);
    await assertFails(getDoc(doc(f, 'chitGroups', 'G1')));
    await assertFails(getDocs(collection(f, 'agents')));
    await assertFails(getDocs(collection(f, 'collections')));
    await assertFails(setDoc(doc(f, 'members', 'X'), { name: 'x', agentIds: [] }));
  });

  it('never lets a client write the admins collection, even an admin', async () => {
    await assertFails(setDoc(doc(db(ADMIN), 'admins', 'someone-else'), { role: 'admin' }));
    await assertFails(setDoc(doc(db(STRANGER), 'admins', STRANGER), { role: 'admin' }));
  });

  it('lets an admin confirm only their own admin document', async () => {
    await assertSucceeds(getDoc(doc(db(ADMIN), 'admins', ADMIN)));
    await assertFails(getDoc(doc(db(AG1), 'admins', ADMIN)));
    await assertFails(getDocs(collection(db(ADMIN), 'admins')));
  });
});

describe('agents (labour accounts)', () => {
  it('lets an agent read only their own agent document', async () => {
    await assertSucceeds(getDoc(doc(db(AG1), 'agents', AG1)));
    await assertFails(getDoc(doc(db(AG1), 'agents', AG2)));
    await assertFails(getDocs(collection(db(AG1), 'agents')));
  });

  it('lets only the admin manage agents, and forbids smuggling extra fields (like a role)', async () => {
    const good = { name: 'New', phone: '9000000001', isActive: true, assignedGroups: [] };
    await assertSucceeds(setDoc(doc(db(ADMIN), 'agents', 'new-agent'), good));
    await assertFails(setDoc(doc(db(ADMIN), 'agents', 'bad'), { ...good, role: 'ADMIN' }));
    await assertFails(setDoc(doc(db(ADMIN), 'agents', 'bad2'), { ...good, pinHash: 'v2$x$y' }));
    await assertFails(setDoc(doc(db(AG1), 'agents', AG1), { ...good }));
    await assertFails(updateDoc(doc(db(AG1), 'agents', AG1), { assignedGroups: ['G9'] }));
    await assertFails(deleteDoc(doc(db(AG1), 'agents', AG2)));
    await assertSucceeds(deleteDoc(doc(db(ADMIN), 'agents', 'agent-off')));
  });

  it('cuts off a deactivated agent immediately', async () => {
    const f = db('agent-off');
    await assertFails(getDoc(doc(f, 'chitGroups', 'G1')));
    await assertFails(getDoc(doc(f, 'members', 'M1')));
  });
});

describe('reference data', () => {
  it('shows an agent only the chits they are assigned to', async () => {
    await assertSucceeds(getDoc(doc(db(AG1), 'chitGroups', 'G1')));
    await assertFails(getDoc(doc(db(AG1), 'chitGroups', 'G9')));
    await assertSucceeds(getDoc(doc(db(AG1), 'members', 'M1')));
    await assertFails(getDoc(doc(db(OUTSIDER), 'members', 'M1')));
  });

  it('allows an agent query only when it carries their own array-contains filter', async () => {
    await assertSucceeds(getDocs(query(collection(db(AG1), 'chitMemberships'), where('agentIds', 'array-contains', AG1))));
    await assertSucceeds(getDocs(query(collection(db(AG1), 'installments'), where('agentIds', 'array-contains', AG1))));
    // a wide-open scan, or a filter for somebody else, is refused
    await assertFails(getDocs(collection(db(AG1), 'chitMemberships')));
    await assertFails(getDocs(query(collection(db(AG1), 'chitMemberships'), where('agentIds', 'array-contains', AG2))));
    await assertFails(getDocs(collection(db(AG1), 'members')));
  });

  it('never lets an agent write reference data', async () => {
    await assertFails(updateDoc(doc(db(AG1), 'chitGroups', 'G1'), { status: 'CLOSED' }));
    await assertFails(setDoc(doc(db(AG1), 'members', 'M2'), { name: 'x', agentIds: [AG1] }));
    await assertFails(deleteDoc(doc(db(AG1), 'installments', 'G1-I1')));
  });

  it('lets the admin read everything and write valid shapes only', async () => {
    const f = db(ADMIN);
    await assertSucceeds(getDocs(collection(f, 'members')));
    await assertSucceeds(getDoc(doc(f, 'chitGroups', 'G9')));
    await assertSucceeds(setDoc(doc(f, 'members', 'M2'), { name: 'Suresh', phone: '9111111111', isActive: true, agentIds: [] }));
    await assertFails(setDoc(doc(f, 'members', 'M3'), { name: 'x', agentIds: [], aadhaarNoEncrypted: 'secret' })); // no ID data in the cloud
    await assertFails(setDoc(doc(f, 'members', 'M4'), { name: 'x' })); // agentIds is mandatory
    await assertFails(setDoc(doc(f, 'members', 'M5'), { name: 'x', isActive: 'yes', agentIds: [] }));
    await assertFails(setDoc(doc(f, 'chitGroups', 'G3'), { name: 'x', chitValue: 'lots', agentIds: [] }));
  });
});

describe('collections (payments taken by agents)', () => {
  it('lets an assigned agent record a payment in their own name', async () => {
    await assertSucceeds(setDoc(doc(db(AG1), 'collections', 'req-1'), collectionDoc()));
  });

  it('refuses a forged payment', async () => {
    const f = db(AG1);
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ agentId: AG2 })));            // someone else's name
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ syncedToAdmin: true })));     // pre-approved
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ groupId: 'G9' })));           // not my chit
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ agentIds: [AG1] })));         // wrong visibility list
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ amountPaise: -5 })));
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ amountPaise: 12.5 })));
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ amountPaise: 999999999999 })));
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ requestId: 'different' })));   // id must be the request id
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ timestamp: new Date('2020-01-01') }))); // back-dated
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ extra: 'field' })));
    await assertFails(setDoc(doc(db(OUTSIDER), 'collections', 'req-1'), collectionDoc({ agentId: OUTSIDER, agentIds: [OUTSIDER] })));
    await assertFails(setDoc(doc(db('agent-off'), 'collections', 'req-1'), collectionDoc({ agentId: 'agent-off' })));
    await assertFails(setDoc(doc(db(STRANGER), 'collections', 'req-1'), collectionDoc({ agentId: STRANGER })));
  });

  it('makes a payment immutable for the agent who wrote it', async () => {
    const f = db(AG1);
    await assertSucceeds(setDoc(doc(f, 'collections', 'req-1'), collectionDoc()));
    await assertFails(updateDoc(doc(f, 'collections', 'req-1'), { amountPaise: 1 }));
    await assertFails(updateDoc(doc(f, 'collections', 'req-1'), { syncedToAdmin: true }));
    await assertFails(setDoc(doc(f, 'collections', 'req-1'), collectionDoc({ amountPaise: 5 })));
    await assertFails(deleteDoc(doc(f, 'collections', 'req-1')));
  });

  it('lets colleagues on the same chit see it, and nobody else', async () => {
    await assertSucceeds(setDoc(doc(db(AG1), 'collections', 'req-1'), collectionDoc()));
    await assertSucceeds(getDoc(doc(db(AG2), 'collections', 'req-1')));
    await assertSucceeds(getDocs(query(collection(db(AG2), 'collections'), where('agentIds', 'array-contains', AG2))));
    await assertFails(getDoc(doc(db(OUTSIDER), 'collections', 'req-1')));
    await assertFails(getDocs(collection(db(AG2), 'collections')));
  });

  it('lets the admin apply it (flip syncedToAdmin) but not rewrite it', async () => {
    await assertSucceeds(setDoc(doc(db(AG1), 'collections', 'req-1'), collectionDoc()));
    const f = db(ADMIN);
    await assertSucceeds(getDocs(query(collection(f, 'collections'), where('syncedToAdmin', '==', false))));
    await assertFails(updateDoc(doc(f, 'collections', 'req-1'), { amountPaise: 1 }));
    await assertFails(updateDoc(doc(f, 'collections', 'req-1'), { syncedToAdmin: true, amountPaise: 1 }));
    await assertSucceeds(updateDoc(doc(f, 'collections', 'req-1'), { syncedToAdmin: true }));
    await assertSucceeds(updateDoc(doc(f, 'collections', 'req-1'), { agentIds: [AG1, AG2, OUTSIDER] }));
    await assertFails(deleteDoc(doc(f, 'collections', 'req-1')));
  });

  it('lets the admin record its own already-applied collection', async () => {
    await assertSucceeds(setDoc(doc(db(ADMIN), 'collections', 'admin-1'), collectionDoc({ agentId: '', agentName: 'Admin', syncedToAdmin: true, requestId: 'admin-1', agentIds: [AG1, AG2] })));
  });
});
