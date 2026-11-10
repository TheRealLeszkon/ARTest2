# Rules

1. Inspect existing code before modifying it.
2. Reuse working components.
3. Do not rewrite the entire application.
4. Work incrementally.
5. Compile after meaningful changes.
6. Verify milestones on the physical device.
7. Keep debugging visualizations available.
8. Do not introduce unnecessary ML models.
9. Do not implement multi-object measurement yet.
10. Do not add server-side measurement.
11. Do not optimize before geometry correctness is established.
12. Do not use PCA as the authoritative measurement method for this iteration.
13. Do not use raw min/max coordinates without outlier rejection.
14. Do not treat individual frames as independent measurements.
15. All accumulated observations must be represented in a common ARCore world coordinate system.
16. When accuracy is poor, identify the failing pipeline stage before changing algorithms.
17. Never claim hardware or accuracy validation without evidence.
18. Prefer small, measurable experiments over large rewrites.
