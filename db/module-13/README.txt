Module 13's schema, copied unchanged from its project seatwave-transactions (commit d78af3a):
V1 to V3 and R__app_grants.sql. They create the bookings table whose price column Module 35's
migrations in db/migration rename to price_paid. Flyway reads both folders in one run.
