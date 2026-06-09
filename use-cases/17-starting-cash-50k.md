# Use Case 17: Starting cash set to 50k

## Summary
Set the starting credit balance for a new game to 50,000 so the player begins with enough cash to exercise trading, outfitting, and refuelling without first grinding. Per the clarification this is a permanent starting balance, not a debug-gated test hack — the real default starting credits for every new game become 50k. This builds on the credits & trading system (UC08) and is a single balance-constant change to whatever currently seeds the player's initial credits at new-game/world-init.

## Acceptance Criteria
1. A newly started game initialises the player's credit balance to exactly 50,000.
2. The value is the actual default (not behind a debug/dev flag), so a normal new game starts with 50k.
3. Loading an existing save is unaffected — only new-game initialisation changes; saved balances persist as stored.
4. The starting-credits value is a single, clearly-named constant/config point, easy to change later.

## Potential Pitfalls & Open Questions
- **Edge case** — If any existing logic assumes a smaller starting balance (e.g. an intro tutorial referencing "you have X credits"), that text/logic should be reconciled or noted.
- **Assumption** — "The beginning" = new-game start, not a top-up applied on every load.

## Original Description
"Also cash to 50k at the beginning as we test."

## Clarifications
- Q: Temporary debug/test setting or permanent starting balance?
  A: Permanent starting balance (50k is the real default starting credits for a new game).
