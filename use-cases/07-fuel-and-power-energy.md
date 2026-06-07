# Use Case 07: Fuel & power/energy

## Summary
Implement the **fuel** system (fuel = the **Hydrogen** resource; minable in UC06 or buyable in UC08) and the **power/energy** model that drives fuel burn: consumption = **base ship draw + sum(installed-module energy use) + active engine/RCS use**. A loaded ship sips fuel even idle; hard maneuvering spikes it; coasting is cheap. **Low fuel reduces effective max speed**, but there is **no stranded/lost-in-space fail state** (the player can always limp to refuel). A refuel action converts hydrogen cargo into fuel (and refuel while docked). Fuel + energy state persist. Ties to UC01 movement (`max_speed` modulation), UC06 cargo, and later upgrades (UC09 fuel-tank/reactor slots).

## Acceptance Criteria
1. The ship has a fuel level (hydrogen) and a fuel-tank capacity stat.
2. Fuel burns at a rate equal to base ship draw + sum of installed-module energy use + active engine/RCS use; coasting costs little, thrusting costs more.
3. Low fuel reduces effective `max_speed` proportionally; fuel never causes a hard stop/stranded state.
4. A power/energy model exposes reactor output and total module draw and feeds the burn rate (rate-based for MVP; explicit pool optional).
5. A refuel action converts hydrogen cargo into fuel (and/or refuels while docked).
6. Fuel and energy state persist across save/reload.
7. Logic is pure and JVM-testable; a recorded playthrough thrusts to burn fuel below the low-fuel threshold and asserts reduced max speed with nonzero remaining fuel.

## Potential Pitfalls & Open Questions
- **Assumption** — Power modeled as a draw-rate feeding fuel burn for MVP (no separate energy bar) unless a reactor pool proves needed.
- **Missing input** — Burn-rate and reactor-output numbers are placeholders to balance later.

## Original Description
Autonomously captured from the Economy & Resources (fuel) and Power & Energy design notes per the owner's request to capture every prepared system.
