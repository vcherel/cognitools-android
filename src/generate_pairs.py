import json
import re

import faiss
import fasttext
import fasttext.util
import numpy as np
import pandas as pd

LEXIQUE_PATH = "data/lexique.tsv"
PAIRS_PATH = "app/src/main/assets/pairs.json"


def family_root(lemma, cgram):
    base = lemma.lower()
    if cgram == "VER":
        return re.sub(r"(er|ir|re)$", "", base)
    if cgram == "ADJ":
        return re.sub(r"(é|ée|i|ant)$", "", base)
    return base


def load_model():
    fasttext.util.download_model("fr", if_exists="ignore")
    return fasttext.load_model("cc.fr.300.bin")


def load_words(lexique_path=LEXIQUE_PATH, min_freq=10, min_length=3):
    df = pd.read_csv(lexique_path, sep="\t")
    df = df[["lemme", "cgram", "freqlemfilms2", "freqlemlivres"]]

    df["freq"] = df["freqlemfilms2"] + df["freqlemlivres"]
    df = df.drop(columns=["freqlemfilms2", "freqlemlivres"])

    df = df.loc[df.groupby("lemme")["freq"].idxmax()]
    df = df[df["freq"] >= min_freq]
    df = df[df["lemme"].str.len() >= min_length]
    df = df[df["cgram"].isin(["NOM", "ADJ"])]

    # Keep a single word per family, favouring nouns then frequency
    df["root"] = df.apply(lambda row: family_root(row["lemme"], row["cgram"]), axis=1)
    df["priority"] = df["cgram"].map({"NOM": 0, "ADJ": 1})
    df = df.sort_values(by=["root", "priority", "freq"], ascending=[True, True, False])
    df = df.drop_duplicates(subset=["root"], keep="first")

    return df.reset_index(drop=True)


def build_pairs(df, ft, neighbors=300, threshold=0.43):
    words = df["lemme"].tolist()
    embeddings = np.array([ft.get_word_vector(w) for w in words], dtype="float32")

    # Inner product equals cosine similarity once vectors are normalised
    embeddings /= np.linalg.norm(embeddings, axis=1, keepdims=True)
    index = faiss.IndexFlatIP(embeddings.shape[1])
    index.add(embeddings)
    similarities, indices = index.search(embeddings, neighbors)

    pairs = []
    for i, word in enumerate(words):
        for j, sim in zip(indices[i], similarities[i], strict=False):
            if i >= j:  # skip self pairs and mirror duplicates
                continue
            if sim > threshold and df.loc[i, "cgram"] == df.loc[j, "cgram"]:
                pairs.append([word.capitalize(), words[j].capitalize()])
    return pairs


def save_pairs(pairs, pairs_path=PAIRS_PATH):
    with open(pairs_path, "w", encoding="utf-8") as f:
        for pair in pairs:
            f.write(json.dumps(pair, ensure_ascii=False) + "\n")


def main():
    ft = load_model()
    df = load_words()
    pairs = build_pairs(df, ft)
    save_pairs(pairs)
    print(f"Saved {len(pairs):,} pairs to {PAIRS_PATH}")


if __name__ == "__main__":
    main()
