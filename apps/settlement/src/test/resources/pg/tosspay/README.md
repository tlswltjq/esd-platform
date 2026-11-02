# Synthetic Toss Pay statement contract samples

These two pages form one complete, synthetic report based on the documented
`POST /api/v2/settlement-details` response shape, reviewed on 2026-11-02:
[Official Toss Pay settlement API](https://docs-pay.toss.im/reference/settlement/detail)

They are handwritten test data, not provider-issued statements or observed LIVE
transactions. There are no credentials. Fee values are examples, not agreed rates.

The approval reports 9,000 with fee -300 / VAT -30. The full refund reports
-9,000 with returned fee 200 / VAT 20. Every page repeats the whole report's
amount 0 / fee -100 / VAT -10 / fee+VAT -110. These fixed expected values are
independent of the production calculation helpers.

The HTTP test server only returns these bodies. It does not calculate fees,
approve payments, manufacture a provider ledger, or require a deployment module.
