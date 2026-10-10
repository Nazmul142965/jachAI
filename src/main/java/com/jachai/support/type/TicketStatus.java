package com.jachai.support.type;

public enum TicketStatus {
    /** Ticket just created, no operator has acknowledged it yet. */
    RECEIVED,
    /** An operator is actively working on the ticket. */
    WORKING_ON_IT,
    /** Operator sent a public reply and is waiting for the requester. */
    WAITING_FOR_YOUR_REPLY,
    /** Operator marked the ticket resolved. Reopenable by the requester. */
    RESOLVED,
    /** Terminal state. Cannot be reopened. */
    CLOSED;

    public boolean isCustomerVisible() {
        return this != null;
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }

    public boolean isReopenable() {
        return this == RESOLVED;
    }
}