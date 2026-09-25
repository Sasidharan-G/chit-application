package com.jothivel.chits.data.local

import com.jothivel.chits.data.local.entity.ActivityLogEntity
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.ChitMembershipEntity
import com.jothivel.chits.data.local.entity.MemberEntity
import com.jothivel.chits.data.models.ChitTemplate
import java.text.SimpleDateFormat
import java.util.Locale

data class NewMemberInput(
    val customerCode: String,
    val name: String,
    val phone: String,
    val address: String = "",
    val city: String = "",
    val nomineeName: String = "",
    val nomineePhone: String = "",
    val nomineeRelationship: String = "",
    val aadhaarLast4: String = "",
    val ticketNo: String = "",
    /** Per-member flat installment in paise; 0 (or the chit's own flat amount) = follow the chit's schedule. */
    val installmentPaise: Long = 0L,
    val joiningDate: String,
    val dueDate: String
)

data class AddMemberResult(val memberId: String, val reusedExistingCustomer: Boolean, val rejoined: Boolean)

/**
 * Adding a customer to a chit, with the identity rules the Add Member screen used to skip:
 *
 * - A customer code that already exists is only reused for the SAME person (same mobile and
 *   name). Typing an existing code for someone else used to silently attach the new chit to the
 *   wrong customer and throw away the name that had just been typed.
 * - A mobile that already belongs to the same-named customer under another code joins that
 *   customer to this chit, instead of being refused.
 * - A customer who left the chit earlier is reactivated rather than blocked as "already added".
 * - Nominee, ticket number and the last four Aadhaar digits (the KYC reference) are captured.
 */
object MemberEnrollmentService {
    private fun parseDate(value: String?) = runCatching {
        SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).apply { isLenient = false }.parse(value.orEmpty().trim())
    }.getOrNull()

    /** The flat monthly amount (paise) a chit would charge if it had no fixed schedule. */
    fun defaultFlatInstallmentPaise(group: ChitGroupEntity): Long =
        if (group.durationMonths > 0) (group.chitValue.toLong() / 100L) / group.durationMonths * 100L else 0L

    fun hasFixedSchedule(group: ChitGroupEntity): Boolean =
        ChitTemplate.forChitValue(group.chitValue / 100)?.fixedSchedule?.size == group.durationMonths

    fun addMemberToChit(db: AppDatabase, groupId: String, input: NewMemberInput): AddMemberResult {
        val code = input.customerCode.trim()
        val name = input.name.trim()
        require(code.isNotBlank()) { "Customer code is required" }
        require(name.isNotBlank()) { "Customer name is required" }
        require(input.phone.length == 10 && input.phone.all(Char::isDigit)) { "Enter a 10-digit mobile number" }
        val joining = parseDate(input.joiningDate)
        val due = parseDate(input.dueDate)
        require(joining != null && due != null) { "Dates must look like 01-Jan-2026" }
        require(!due.before(joining)) { "Due date cannot be before the joining date" }
        require(input.aadhaarLast4.isBlank() || (input.aadhaarLast4.length == 4 && input.aadhaarLast4.all(Char::isDigit))) {
            "Enter exactly the last 4 digits of the Aadhaar number"
        }
        require(input.nomineePhone.isBlank() || (input.nomineePhone.length == 10 && input.nomineePhone.all(Char::isDigit))) {
            "Nominee mobile must be 10 digits"
        }
        val ticket = input.ticketNo.trim()

        var result: AddMemberResult? = null
        db.runInTransaction {
            val group = db.groupDao().getGroupByIdSync(groupId) ?: throw IllegalStateException("Selected chit was not found")
            check(group.status.isNullOrBlank() || group.status == "ACTIVE") { "This chit is closed" }
            if (ticket.isNotBlank()) {
                val number = ticket.toIntOrNull()
                require(number != null && number in 1..group.subscriberCount) { "Ticket number must be between 1 and ${group.subscriberCount}" }
            }

            val byCode = db.memberDao().getMemberByIdIgnoreCaseSync(code)
            val byPhone = db.memberDao().getMemberByPhoneSync(input.phone)
            val existing = when {
                byCode != null -> {
                    check(byCode.phone == input.phone && byCode.name.orEmpty().trim().equals(name, ignoreCase = true)) {
                        "Customer code ${byCode.id} already belongs to ${byCode.name.orEmpty()} (${byCode.phone.orEmpty()}). Use a different code."
                    }
                    byCode
                }
                byPhone != null -> {
                    check(byPhone.name.orEmpty().trim().equals(name, ignoreCase = true)) {
                        "Mobile ${input.phone} already belongs to ${byPhone.name.orEmpty()} (${byPhone.id})."
                    }
                    byPhone
                }
                else -> null
            }

            val member = existing ?: MemberEntity().apply {
                id = code
                this.name = name
                phone = input.phone
                photoUrl = null
                nomineeName = input.nomineeName.trim().ifBlank { null }
                nomineePhone = input.nomineePhone.trim().ifBlank { null }
                nomineeRelationship = input.nomineeRelationship.trim().ifBlank { null }
                role = "MEMBER"
                isActive = true
                dob = null
                gender = null
                addressLine = input.address.trim()
                city = input.city.trim()
                state = "Tamil Nadu"
                pincode = null
                // Only the last four digits are ever kept: enough to identify the ID document on
                // file, without storing a full Aadhaar number.
                aadhaarNoEncrypted = input.aadhaarLast4.takeIf { it.isNotBlank() }?.let { "XXXXXXXX$it" }
                panNo = null
                aadhaarDocumentPath = null
                panDocumentPath = null
                selectedChitId = group.id
                ticketNo = ticket.ifBlank { null }
                installmentAmount = if (input.installmentPaise > 0) (input.installmentPaise / 100).toString() else ""
                joiningDate = input.joiningDate.trim()
                dueDate = input.dueDate.trim()
            }

            val current = db.membershipDao().getSync(member.id, group.id)
            if (current?.isActive == true) throw IllegalStateException("This customer is already in this chit")
            check(db.membershipDao().countActiveForGroupSync(group.id) < group.subscriberCount) { "This chit is full (${group.subscriberCount} members)" }
            if (ticket.isNotBlank()) {
                check(db.membershipDao().countTicketUsedByOthersSync(group.id, ticket, member.id) == 0) { "Ticket $ticket is already taken in this chit" }
            }

            if (existing == null) db.memberDao().insertMember(member)
            // A flat amount equal to the chit's own default is stored as "no override", so the
            // last installment (which carries the paise remainder) is collected in full.
            val overridePaise = if (hasFixedSchedule(group) || input.installmentPaise == defaultFlatInstallmentPaise(group)) 0L else input.installmentPaise
            if (current != null) {
                db.membershipDao().setActive(member.id, group.id, true)
                if (ticket.isNotBlank()) db.membershipDao().updateTicketNo(member.id, group.id, ticket)
            } else {
                db.membershipDao().insert(ChitMembershipEntity().apply {
                    id = "${member.id}:${group.id}"
                    memberId = member.id
                    this.groupId = group.id
                    ticketNo = ticket.ifBlank { null }
                    installmentAmountPaise = overridePaise
                    joiningDate = input.joiningDate.trim()
                    dueDate = input.dueDate.trim()
                    isActive = true
                })
            }
            db.activityLogDao().insertLog(ActivityLogEntity(actionType = "MEMBER_ADDED", title = "Member Added", description = "${member.name} joined ${group.registerNo}"))
            result = AddMemberResult(member.id, reusedExistingCustomer = existing != null, rejoined = current != null)
        }
        return checkNotNull(result)
    }
}
