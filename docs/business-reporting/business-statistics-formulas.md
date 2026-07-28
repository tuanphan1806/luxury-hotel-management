# Business statistics formulas

All monetary values are VND. Period membership is evaluated in
`Asia/Ho_Chi_Minh`.

## Revenue and cash

- `recognizedRevenue = SUM(invoice.totalAmount)` for invoices issued in period.
- `roomRevenue`, `addOnRevenue`, `additionalFee`, `lateCheckoutFee`, discount
  and tax come from immutable invoice columns/snapshot.
- `otherRevenue = recognizedRevenue - roomRevenue - addOnRevenue -
  additionalFee - lateCheckoutFee`, with the stored invoice breakdown used
  rather than current catalog values.
- `grossCashInflow = SUM(canonical receivedAmount) + SUM(unmatched provider in)`.
- `acceptedCashInflow = SUM(payment.acceptedAmount)`.
- `unacceptedReceived = grossCashInflow - acceptedCashInflow`. This includes
  overpayment and unmatched cash and is therefore accompanied by data-quality
  details.
- `refundOutflow = SUM(refund.actualRefundAmount)` for `SUCCEEDED` refunds.
- `netCashFlow = grossCashInflow - refundOutflow`.
- `netBankMovement = grossCashInflow - refundOutflow -
  unclassifiedCashOutflow`.

`netCashFlow` is the canonical payment/refund view. `netBankMovement` also
reflects bank outflow not yet classified as a valid refund; it must not be
labelled refund until matched.

## Open balances

- `netAccepted(reservation) = max(accepted payments - succeeded refunds, 0)`.
- `outstandingReceivables = SUM(max(totalAmount - netAccepted, 0))` for
  `CHECKED_IN` reservations.
- `customerDeposits = SUM(netAccepted)` for open reservations (`DRAFT`,
  `CONFIRMED`, `CANCELLATION_PENDING`, `CHECKED_IN`).
- `refundPayable = active refund request amount + uncovered mandatory payment
  refund amount + uncovered cancellation refund amount`.

Active statuses include waiting for recipient, ready for transfer, requested,
processing, manual review and failed. `CANCELLED` is excluded as an active row
but also excluded from obligation coverage, so the uncovered amount remains.

## Occupancy

For each hotel day and each committed reservation:

- Start: actual check-in for checked-in/checked-out reservations when present;
  otherwise planned check-in.
- End: actual checkout for checked-out reservations when present; planned
  checkout for confirmed reservations; for an active checked-in stay, at least
  the later of planned checkout and current hotel time.
- `soldRoomHours = overlap(stay, report day) in hours * room quantity`.
- `availableRoomHours = sellable room count for day * 24`.
- `roomNightEquivalent = soldRoomHours / 24`.
- `availableRoomNightEquivalent = availableRoomHours / 24`.
- `occupancyRate = soldRoomHours / availableRoomHours * 100`.

Hourly calculation is intentional because the hotel sells short stays. Counting
every stay as one room-night would overstate two-hour reservations.

## ADR and RevPAR

Invoice room revenue is allocated to a report period by actual/planned stay
overlap:

`allocatedRoomRevenue = invoiceRoomRevenue * overlapSeconds /
totalStaySeconds`.

- `ADR = allocatedRoomRevenue / roomNightEquivalent`.
- `RevPAR = allocatedRoomRevenue / availableRoomNightEquivalent`.

The same overlap basis is used for room-type performance, preventing a checkout
date from assigning all room revenue to only the final day.

## Booking trend

Bookings are grouped by reservation `created_at`, while each bucket reports the
reservation's current outcome/status. It is not a historical status-transition
timeline; that would require an event snapshot model.
