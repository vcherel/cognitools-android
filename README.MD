J'ai commencé à créer une application android (Android version 8.0), qui a pour but de regrouper divers fonctionnalités tout en restant le plus rapide et minimale possible.


Menu principal
    * Affiche les différentes fonctionnalités sous forme de boutons ou liste :
        * Générateur aléatoire
        * Volume booster
        * Flashcards
    * Chaque bouton ouvre l’écran correspondant dans la même MainActivity via Compose (Composable).



Écran Générateur aléatoire
    * Deux sections :
        * Nombre entier aléatoire :
            * Par défaut, génère un entier entre 1 et 100.
            * Permet à l’utilisateur de modifier les bornes minimale et maximale avant de générer.
            * Bouton “Générer” qui affiche le résultat.
        * Mot aléatoire :
            * Génère un mot aléatoire en français à partir d’une liste intégrée dans l’application (ex : ["chat", "maison", "arbre", ...]).
            * Bouton “Générer” qui affiche le mot.