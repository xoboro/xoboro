# Non-REST protocol inventory

Komga's generated OpenAPI document omits several protocol controllers. Xoboro
therefore locks those routes separately from the 165-operation REST baseline.

| Surface | Operations | Komga 1.25.0 source SHA-256 |
|---|---:|---|
| OPDS v1 | 17 | `adb45e3fd49bfeaa747b15ee6701c0530520385fd7a695662450c9e090a535d8` |
| OPDS v2 | 27 | `17c0b704b0fc9b920a51783b55a3de9fed352b65a512724662f0bc827cdcb5b6` |
| Kobo | 15 | `2add95ed52e455c4c16ea4983d26c6dd1d9ba05c90b8c675cd5e6baa0b9a168b` |
| KOReader | 4 | `dd7d69c6c255e1269ca91b1161eac50419208b2b4be772bfad1f4a1b5752b9dc` |
| SSE | 1 | `643833b6a4b4efdd002a94b728ae462c807f38255325a37eb7c61dce7dd239b6` |
| Total | 64 | |

The route inventory test treats expanded mapping arrays and the five Kobo
catch-all HTTP methods as distinct observable operations.

Together with the REST baseline, the current externally addressable contract
contains 229 HTTP operations. This does not include scheduled jobs, database
migration behavior, file-format semantics, or configuration behavior, which
have their own parity gates.
