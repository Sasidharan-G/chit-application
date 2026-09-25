package com.jothivel.chits.data.local.dao;

/** Result row of [PaymentDao.getCashCollectedPerAgentSync]: total cash one agent has collected. */
public class AgentCashRow {
    public String agentId;
    public String agentName;
    public long totalPaise;
}
