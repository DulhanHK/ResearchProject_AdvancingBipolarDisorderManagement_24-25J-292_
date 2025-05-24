# ResearchProject_AdvancingBipolarDisorderManagement_24-25J-292_
# Personalized Suggestions and Activities for Mood and Behavior Management

## Overview

This module is a crucial part of the \*\*Advancing Bipolar Disorder Management\*\* project, designed to provide \*\*personalized suggestions and activities\*\* to assist users in managing their moods and behaviors. By using advanced data analysis and recommendation algorithms, this system offers tailored insights based on individual user data, enhancing emotional well-being and supporting effective mood management.

# Technologies Used
## 1\. Backend:
Framework: FastAPI

\- Libraries:

\- \`scikit-learn\` (data encoding and scaling)

\- \`pandas\` and \`numpy\` (data preprocessing)

\- \`TfidfVectorizer\` (text vectorization)

\- Language: Python

## 2\. Mobile Application:

\- Framework: Android Studio

\- Language: Kotlin

## 3\. AI Techniques:

\- TF-IDF Vectorization

\- One-Hot Encoding

\- Cosine Similarity

## 4\. Dataset:

\- CSV-based dataset (\`Updated\_Bipolar\_Dataset.csv\`).


# How to Download and Run

### Downloading the Project

1\. Navigate to the project repository.

2\. Click the \*\*Code\*\* button and select \*\*Download ZIP\*\*.

3\. Extract the ZIP file to your system.

\---

### Running the Backend Server

1. Navigate to the \`Backend\` directory:

\`\`\`bash

cd Backend


2. python -m venv venv

.\\venv\\Scripts\\Activate.ps1 # For Windows PowerShell


3. pip install -r requirements.txt


4. uvicorn main:app --reload --host 0.0.0.0


# Flow of the module

1. User Inputs via Mobile App
   - User provides mood, bipolar stage, age, and gender.

2. Backend Server Receives Data
   - Mobile app sends the input data to the backend server.

3. Data Preprocessing
   - The server processes and handles any necessary data cleaning and transformation.

4. Feature Encoding and Similarity Calculation
   - Input features are encoded (e.g., mood, age) and similarity calculations are performed to match the user data with pre-existing records.

5. Ranking and Selecting Recommendations
   - Based on similarity scores, the server ranks recommendations and selects the most relevant ones.

6. Return Top Activities and Suggestions
   - The server sends the top 5 personalized activities and suggestions back to the mobile app.

7. Display Recommendations on Mobile App
   - The mobile app displays the recommended activities and suggestions to the user.
