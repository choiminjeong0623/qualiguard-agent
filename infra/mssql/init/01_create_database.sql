-- QualiGuard 데이터베이스 생성 (이미 있으면 건너뜀)
IF DB_ID(N'QUALIGUARD') IS NULL
BEGIN
    CREATE DATABASE QUALIGUARD COLLATE Korean_Wansung_CI_AS;
END
GO
SELECT name, collation_name FROM sys.databases WHERE name = N'QUALIGUARD';
GO
