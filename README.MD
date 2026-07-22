# CogniTools

An Android app built in Kotlin for **fun**, designed to group all the little tools and games I like to have on my phone.

<p align="center">
  <img src="/images/main_menu.jpeg" alt="Main menu" width="300">
</p>

### Features
- **Random Number & Word Generator**

Quickly generate random numbers or words whenever you need a little randomness in your day.

- **Volume Booster**

Increase or decrease your phone's sound volume beyond the default limits.

- **Flashcards for Learning**

Learn word pairs efficiently. The app intelligently shows cards we know less often, so you focus on what you need to remember.

<p align="center">
  <img src="/images/flashcard_menu.jpeg" alt="Flashcard menu" width="300" style="margin-right: 20px;">
  <img src="/images/flashcard.jpeg" alt="Flashcard game" width="300" style="margin-left: 20px;">
</p

- **Undercover Game**

Play a social deduction game where each player receives a secret word. Most players get the same word, but one or multiple ***Undercover*** have a different word and/or one or multiple ***Mr. White*** don’t get any word at all. Players take turns giving clues about their word, trying not to reveal it. The goal is to identify the undercovers while undercovers try to blend in.

The pairs of words used in the game were generated automatically using **NLP** and **FastText embeddings**. Using this method, the game includes approximatlely 80,000 pairs of related words.

The code used to generate the pairs lives in `src/generate_pairs.py`, with `generate_dataset.ipynb` as a thin driver to run and inspect it. The project uses [uv](https://docs.astral.sh/uv/) for the environment. To set it up and generate the pairs, run:
```bash
uv sync
uv run python src/generate_pairs.py
```
To explore the results in the notebook instead:
```bash
uv sync --group notebook
uv run jupyter notebook generate_dataset.ipynb
```

- **Wikipedia Reader**

Explore random Wikipedia pages

- **Weather Forecast**

Check the forecast for your current GPS location or a searched city, with temperature, weather conditions, and rain amount.

- **Notes**

A lightweight notes tool with checkbox and separator lines, PIN lock per note, and a Courses/Ingrédients sync mode that keeps a shopping list and an ingredients list reconciled with each other.

- **Gallery**

A lightweight photo/video browser: albums from your device's folders, a full-screen swipe viewer with pinch zoom and video playback, plus rename, move, delete, crop, and video trim.
