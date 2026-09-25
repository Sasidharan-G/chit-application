package com.jothivel.chits.data.firebase

/** Collection names and field keys shared by every Firestore read/write in the Labour feature. */
object FirestoreSchema {
    const val AGENTS = "agents"
    const val COLLECTIONS = "collections"
    const val CHIT_GROUPS = "chitGroups"
    const val MEMBERS = "members"
    const val CHIT_MEMBERSHIPS = "chitMemberships"
    const val INSTALLMENTS = "installments"

    /** Field on reference data / collections listing the UIDs of the active agents allowed to read it. */
    const val AGENT_IDS = "agentIds"

    object Agent {
        const val NAME = "name"
        const val PHONE = "phone"
        const val AUTH_EMAIL = "authEmail"
        const val GEN = "gen"
        const val IS_ACTIVE = "isActive"
        const val ASSIGNED_GROUPS = "assignedGroups"
        const val CREATED_AT = "createdAt"
        const val CREATED_BY = "createdBy"
    }

    object Collection {
        const val AGENT_ID = "agentId"
        const val AGENT_NAME = "agentName"
        const val MEMBER_ID = "memberId"
        const val MEMBER_NAME = "memberName"
        const val GROUP_ID = "groupId"
        const val CHIT_NO = "chitNo"
        const val AMOUNT_PAISE = "amountPaise"
        const val MODE = "mode"
        const val REFERENCE_NO = "referenceNo"
        const val RECEIPT_NO = "receiptNo"
        const val NOTES = "notes"
        const val BUSINESS_DATE = "businessDate"
        const val TIMESTAMP = "timestamp"
        const val STATUS = "status"
        const val SYNCED_TO_ADMIN = "syncedToAdmin"
        const val REQUEST_ID = "requestId"
        const val AGENT_IDS = "agentIds"
    }
}
