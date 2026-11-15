-- =====================================================================
-- 유사 사례 의미 검색 (ADR-014) — SQL Server 2025 VECTOR 형식
-- 여러 번 실행해도 안전하다. 벡터는 앱이 만든다 (NcrEmbeddingService, Gemini 임베딩 768차원).
-- =====================================================================
USE QUALIGUARD;
GO

IF OBJECT_ID(N'dbo.ncr_embedding', N'U') IS NULL
CREATE TABLE dbo.ncr_embedding (
    ncr_id       INT               NOT NULL CONSTRAINT pk_ncr_embedding PRIMARY KEY
                                   CONSTRAINT fk_ncr_embedding_ncr REFERENCES dbo.ncr (ncr_id),
    embedding    VECTOR(768)       NOT NULL,
    model        VARCHAR(50)       NOT NULL,
    text_hash    CHAR(64)          NOT NULL,           -- 벡터를 만든 글의 SHA-256 (내용이 바뀌면 다시 만듦)
    created_at   DATETIMEOFFSET(3) NOT NULL CONSTRAINT df_ncr_embedding_created DEFAULT (SYSDATETIMEOFFSET())
);
GO

SELECT name AS vector_object FROM sys.objects WHERE name = N'ncr_embedding';
GO
