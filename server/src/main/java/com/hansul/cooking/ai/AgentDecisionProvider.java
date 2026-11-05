package com.hansul.cooking.ai;

import com.hansul.cooking.TurnDecision;

/** Replace this port when changing cloud or local inference providers. */
public interface AgentDecisionProvider {
    TurnDecision decide(AgentTurnContext context);
}
