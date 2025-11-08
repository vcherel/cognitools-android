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

The code used to generate the pairs is available in generate_dataset.ipynb. To install all necessary libraries, run:
```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python3 -m spacy download fr_core_news_lg
```

- **Wikipedia Reader**

Explore random Wikipedia pages
