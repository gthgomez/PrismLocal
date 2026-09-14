# PrismLocal Inference Roadmap and Agent Execution Program

**Research date:** September 14, 2026 · **Repository:** `gthgomez/PrismLocal` · **Default branch at inspection:** `main`  
**Audited commit:** `49ed799a3e633c5192c2c03317d1d50ffdc57c4c`  
**Actual llama.cpp gitlink:** `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`  
**Upstream comparison candidate:** `661643e43079a4ee6faab4c1895291767b67ea8d`

> **Evidence boundary:** This is a live-source audit, current primary-source synthesis and proposed executable engineering program. No Android app build, physical-phone inference benchmark or coding-agent implementation was executed in this research session. Static counterexamples and artifact-tool checks are labeled separately. No throughput, energy, memory or thermal improvement is claimed to have been measured. Source-derived defects are distinguished from race risks, hypotheses, reported external issues and untested opportunities. The report does not certify every competing engine’s full source tree or every model/backend combination.

**Decision:** keep llama.cpp as the primary engine; fix correctness, runtime contracts and measurement first; qualify CPU/Vulkan/OpenCL and optional upstream Hexagon through isolated tests; introduce a second engine only if an equivalent-quality integrated bake-off earns its complexity.

## Contents
1. Executive verdict
2. PrismLocal’s current inference architecture
3. Verified and falsified prior hypotheses
4. Current strengths of Prism
5. Current weaknesses and bottlenecks
6. llama.cpp upstream gap analysis
7. PocketPal analysis
8. llama.rn analysis
9. MNN analysis
10. MLC LLM analysis
11. ExecuTorch analysis
12. LiteRT-LM analysis
13. ChatterUI analysis
14. SmolChat and current equivalent analysis
15. Additional engines and optimization systems discovered
16. Adversarial pass on every engine and reconciliation
17. Cross-engine comparison matrix
18. Unexplored optimization opportunities
19. Features Prism should borrow
20. Features Prism should explicitly reject
21. PrismLocal Adaptive Inference Runtime target architecture
22. Detailed code and file mapping
23. Benchmark architecture and science
24. Correctness gates
25. Alternative-runtime decision matrix
26. Prioritized feature backlog and scoring
27. Implementation DAG and critical path
28. Parallel execution map and worktree ownership
29. Complete agent mission packets
30. Root integrator mission
31. Risk register
32. Kill criteria and experiment retention policy
33. Final recommended execution order

