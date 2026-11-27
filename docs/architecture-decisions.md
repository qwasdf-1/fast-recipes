# Architecture decisions

## Standalone server-side Fabric target

Use the same single-project wrapper and Loom conventions as Function Compiler.
Fast Recipes has its own module ID and an Apache-2.0 license. The initial
target is Minecraft 26.3 on a dedicated server with a matching vanilla client.

## Research source set

Keep isolated experiments in `benchmarks/java`. The alpha packages opt-in
runtime hooks in the regular JAR. Enable a runtime feature by default only
when it passes the decision gate for correctness, practical performance,
and memory cost. The [research decision](research/R-006-decision.md) records
the current disposition and remaining evidence.
