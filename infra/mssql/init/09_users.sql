-- =====================================================================
-- QMS 화면 사용자 (로그인·권한, ADR-011)
-- 여러 번 실행해도 안전하다. 계정은 여기서 만들지 않는다 (비밀번호를 저장소에 남기지 않기 위해).
-- 앱이 시작할 때 .env 의 QG_REVIEWER_* / QG_VIEWER_* 값으로, 없는 계정만 BCrypt 해시로 만든다.
--
-- 역할: REVIEWER(품질 담당자 — 초안 수정·승인·반려·조치 완료), VIEWER(조회만)
-- =====================================================================
USE QUALIGUARD;
GO

IF OBJECT_ID(N'dbo.app_user', N'U') IS NULL
CREATE TABLE dbo.app_user (
    username       VARCHAR(50)       NOT NULL CONSTRAINT pk_app_user PRIMARY KEY,
    display_name   NVARCHAR(50)      NOT NULL,          -- 검토 이력에 남는 이름
    password_hash  VARCHAR(100)      NOT NULL,          -- BCrypt ({bcrypt}$2a$...)
    role           VARCHAR(20)       NOT NULL CONSTRAINT ck_app_user_role CHECK (role IN ('REVIEWER', 'VIEWER')),
    enabled        BIT               NOT NULL CONSTRAINT df_app_user_enabled DEFAULT (1),
    created_at     DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_app_user_created DEFAULT (SYSDATETIMEOFFSET())
);
GO

SELECT name AS user_object FROM sys.objects WHERE name = N'app_user';
GO
