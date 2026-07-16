# Contributing

`cloud-itonami-isic-4763` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model.

## Development

```bash
clojure -M:test
clojure -M:lint
```

## Rules
- Do not commit real customer, employee, supplier or
  product-safety-incident data.
- Keep sales-record logging, staffing-operation scheduling, supply-order
  coordination and safety-concern flagging behind the
  SportsRetailGovernor.
- Treat retail-store-operations workflows as high-risk: add tests for
  store/vendor verification, effect discipline, scope exclusion,
  escalation and audit logging.
- Never phrase a governor scope-exclusion term as a bare noun (e.g.
  "recall", "certification") -- phrase it as the finalization/execution
  ACTION (e.g. "issued the recall"), and add/extend the
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  regression test for any new term. A bare-noun term will self-trip this
  actor's own legitimate `:flag-safety-concern` happy path -- see
  `sportsretailops.governor/scope-excluded-terms`'s docstring.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
