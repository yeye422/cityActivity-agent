#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["pinecone>=10.0.0"]
# ///

import os
from pinecone import Pinecone, SchemaBuilder

api_key = os.environ["PINECONE_API_KEY"]
index_name = os.environ.get("PINECONE_INDEX_NAME", "city-activities")
dimension = int(os.environ.get("DASHSCOPE_EMBEDDING_DIMENSIONS", "256"))
cloud = os.environ.get("PINECONE_CLOUD", "aws")
region = os.environ.get("PINECONE_REGION", "us-east-1")

pc = Pinecone(api_key=api_key)

schema = (
    SchemaBuilder()
    # Pinecone 当前 FTS 不提供中文 language analyzer；字符 n-gram 用于中文词面召回。
    .add_string_field(
        "body",
        full_text_search={"ngram": {"min_gram": 2, "max_gram": 4}},
    )
    .add_dense_vector_field("embedding", dimension=dimension, metric="cosine")
    .build()
)

if not pc.indexes.exists(index_name):
    pc.indexes.create(
        name=index_name,
        schema=schema,
        deployment={
            "deployment_type": "managed",
            "cloud": cloud,
            "region": region,
        },
    )

desc = pc.indexes.describe(index_name)
print(f"PINECONE_INDEX_NAME={index_name}")
print(f"PINECONE_INDEX_HOST={desc.host}")
print(f"schema={desc.schema}")
