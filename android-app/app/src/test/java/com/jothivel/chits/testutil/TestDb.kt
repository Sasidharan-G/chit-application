package com.jothivel.chits.testutil

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jothivel.chits.data.local.AppDatabase
import com.jothivel.chits.data.local.entity.ChitGroupEntity
import com.jothivel.chits.data.local.entity.ChitMembershipEntity
import com.jothivel.chits.data.local.entity.InstallmentEntity
import com.jothivel.chits.data.local.entity.MemberEntity

/** Shared fixtures for the Robolectric tests: a real in-memory Room database plus tiny seeders. */
object TestDb {
    fun newDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /** A chit of [months] flat installments of [installmentPaise] each, starting on [start]. */
    fun seedGroup(
        db: AppDatabase,
        id: String = "G1",
        registerNo: String = "REG-$id",
        months: Int = 3,
        installmentPaise: Int = 100_00,
        start: String = "01-Jan-2026",
        status: String = "ACTIVE",
        subscribers: Int = months
    ): ChitGroupEntity {
        // Local copies: inside `Entity().apply { ... }` a bare `id` / `status` resolves to the ENTITY's
        // own field, not the function parameter, which silently self-assigns (or fails to compile).
        val gid = id
        val reg = registerNo
        val label = "Chit $id"
        val state = status
        val count = months
        val per = installmentPaise
        val startText = start
        val subs = subscribers
        val group = ChitGroupEntity().apply {
            this.id = gid
            name = label
            this.registerNo = reg
            chitValue = per * count
            durationMonths = count
            subscriberCount = subs
            branch = "Main"
            startDate = startText
            this.status = state
        }
        db.groupDao().insertGroup(group)
        db.installmentDao().insertAll((1..count).map { no ->
            InstallmentEntity().apply {
                this.id = "$gid-I$no"
                this.groupId = gid
                installmentNo = no
                baseAmount = per
                kasaruAmount = 0
                this.status = "UPCOMING"
            }
        })
        return group
    }

    fun seedMember(db: AppDatabase, id: String, name: String = "Member $id", phone: String = "9" + id.filter(Char::isDigit).padEnd(9, '0').take(9)): MemberEntity {
        val mid = id
        val mname = name
        val mphone = phone
        val member = MemberEntity().apply {
            this.id = mid
            this.name = mname
            this.phone = mphone
            role = "MEMBER"
            isActive = true
        }
        db.memberDao().insertMember(member)
        return member
    }

    fun seedMembership(db: AppDatabase, memberId: String, groupId: String, active: Boolean = true, ticket: String? = null): ChitMembershipEntity {
        val mid = memberId
        val gid = groupId
        val ticketValue = ticket
        val activeValue = active
        val membership = ChitMembershipEntity().apply {
            id = "$mid:$gid"
            this.memberId = mid
            this.groupId = gid
            ticketNo = ticketValue
            installmentAmountPaise = 0L
            isActive = activeValue
        }
        db.membershipDao().upsertAll(listOf(membership))
        return membership
    }
}
