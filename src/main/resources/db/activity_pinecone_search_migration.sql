-- Pinecone 接管词面与向量检索后，本地不再持久化 embedding。
USE city_db;

DROP TABLE IF EXISTS activity_embedding;
