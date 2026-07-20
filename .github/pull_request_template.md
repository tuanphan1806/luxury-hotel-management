## Summary

Describe the problem and the resulting behavior in clear business terms.

## Change type

- [ ] Feature
- [ ] Bug fix
- [ ] Refactor or maintenance
- [ ] Database migration
- [ ] Payment/refund/reservation workflow
- [ ] Documentation only

## Verification

- [ ] Backend tests pass
- [ ] PostgreSQL/Flyway migration gates pass, or not applicable
- [ ] Frontend lint passes, or not applicable
- [ ] Frontend production build passes, or not applicable
- [ ] Manual workflow was tested, or not applicable
- [ ] Concurrency/idempotency behavior was considered

## Contract and operations impact

- API or event contract changes:
- Database migration and rollback:
- Environment/configuration changes:
- Monitoring/audit impact:
- Deployment and rollback steps:

## Risk-sensitive checklist

- [ ] No secret, `.env`, production data, log, or generated build output is included
- [ ] No payment/refund amount can be applied twice
- [ ] No reservation, RoomHold, room assignment, or checkout invariant is weakened
- [ ] A rollback path is documented for high-risk changes
