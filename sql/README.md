# Database migrations

MySQL schema and reference data are managed by Flyway.

The canonical, versioned migrations live in:

```text
src/main/resources/db/migration/
```

Do not edit an applied migration. Add a new `V<version>__<description>.sql` file for every schema or reference-data change. Spring Boot runs pending migrations before application beans access the database.

## Existing databases

A non-empty legacy Study-Agent database is adopted at baseline version `1`; Flyway then applies `V2` and later migrations. Reference-data migrations use insert-if-missing semantics so customized rows are preserved.

Baselining does not infer or repair unknown schema drift in an older database. Audit any such differences and add an explicit `V3` or later migration instead of editing `V1` or `V2`.
