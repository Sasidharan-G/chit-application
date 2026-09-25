package com.jothivel.chits.data.local

import com.jothivel.chits.data.local.entity.ActivityLogEntity

/**
 * Saves a single cell edited inline on the ledger sheet. This used to be a bare DAO update fired
 * from an unguarded coroutine: a duplicate ticket number threw a constraint exception that crashed
 * the app, a phone number could be anything (or a duplicate), a blank ticket was stored as "" (which
 * then collided with the next blank one), and nothing was logged. Now every edit is validated,
 * throws [IllegalArgumentException] with a readable message when refused, and leaves an audit entry.
 */
object LedgerEditService {
    // Column positions on the ledger sheet (see ResizableLedgerSheet).
    const val COL_TICKET = 8
    const val COL_OLD_CODE = 9
    const val COL_PHONE = 10
    const val COL_NAME = 11
    const val COL_ADDRESS = 12
    const val COL_CITY = 13

    private fun label(column: Int) = when (column) {
        COL_TICKET -> "Ticket"
        COL_OLD_CODE -> "Old code"
        COL_PHONE -> "Phone"
        COL_NAME -> "Name"
        COL_ADDRESS -> "Address"
        COL_CITY -> "City"
        else -> "Column $column"
    }

    fun edit(db: AppDatabase, memberId: String, groupId: String, column: Int, rawValue: String) {
        val value = rawValue.trim()
        db.runInTransaction {
            val member = db.memberDao().getMemberByIdSync(memberId) ?: throw IllegalArgumentException("Customer was not found")
            val before: String? = when (column) {
                COL_TICKET -> db.membershipDao().getSync(memberId, groupId)?.ticketNo
                COL_OLD_CODE -> member.panNo // the "old code" has always been kept in this column
                COL_PHONE -> member.phone
                COL_NAME -> member.name
                COL_ADDRESS -> member.addressLine
                COL_CITY -> member.city
                else -> throw IllegalArgumentException("This column cannot be edited")
            }
            when (column) {
                COL_NAME -> {
                    require(value.isNotBlank()) { "Name cannot be empty" }
                    require(value.length <= 80) { "Name is too long" }
                    db.memberDao().updateName(memberId, value)
                }
                COL_PHONE -> {
                    require(value.length == 10 && value.all(Char::isDigit)) { "Enter a 10-digit mobile number" }
                    val owner = db.memberDao().getMemberByPhoneSync(value)
                    require(owner == null || owner.id == memberId) { "Mobile $value already belongs to ${owner?.name.orEmpty()} (${owner?.id})" }
                    db.memberDao().updatePhone(memberId, value)
                }
                COL_TICKET -> {
                    val membership = db.membershipDao().getSync(memberId, groupId) ?: throw IllegalArgumentException("This customer is not in the chit")
                    if (value.isBlank()) {
                        db.membershipDao().updateTicketNo(memberId, groupId, null)
                    } else {
                        val group = db.groupDao().getGroupByIdSync(groupId) ?: throw IllegalArgumentException("Chit was not found")
                        val number = value.toIntOrNull()
                        require(number != null && number in 1..group.subscriberCount) { "Ticket must be a number from 1 to ${group.subscriberCount}" }
                        require(db.membershipDao().countTicketUsedByOthersSync(groupId, value, membership.memberId) == 0) { "Ticket $value is already taken in this chit" }
                        db.membershipDao().updateTicketNo(memberId, groupId, value)
                    }
                }
                COL_OLD_CODE -> {
                    require(value.length <= 30) { "Old code is too long" }
                    db.memberDao().updateOldCode(memberId, value.ifBlank { null })
                }
                COL_ADDRESS -> {
                    require(value.length <= 120) { "Address is too long" }
                    db.memberDao().updateAddress(memberId, value)
                }
                COL_CITY -> {
                    require(value.length <= 60) { "City is too long" }
                    db.memberDao().updateCity(memberId, value)
                }
            }
            db.activityLogDao().insertLog(
                ActivityLogEntity(
                    actionType = "LEDGER_EDIT",
                    title = "Ledger ${label(column).lowercase()} edited",
                    description = "$memberId • ${before.orEmpty().ifBlank { "-" }} → ${value.ifBlank { "-" }}"
                )
            )
        }
    }
}
