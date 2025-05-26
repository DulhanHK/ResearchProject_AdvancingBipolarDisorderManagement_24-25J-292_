from flask import request, jsonify
from models import load_text_model, load_bipolar_model
from utils import preprocess_text, preprocess_audio, preprocess_bipolar_inputs
from database import store_prediction
from werkzeug.utils import secure_filename
from pydub import AudioSegment
import os
import numpy as np
import pandas as pd
import tempfile
import uuid
import whisper
import librosa
from pydub import AudioSegment

# Load models
text_model = load_text_model()
# audio_model = load_audio_model()
bipolar_model, bipolar_label_encoder = load_bipolar_model()
whisper_model = whisper.load_model('base')

# Define emotion labels (adjust based on your models)
emotion_labels = ['Depressive', 'Fear', 'Happy', 'Manic', 'Neutral', 'Sad', 'Shocked', 'Surprised']
# Configuration
ALLOWED_AUDIO_EXTENSIONS = {'webm', 'mp3', 'wav', 'ogg'}
MAX_AUDIO_SIZE_MB = 5  # 5MB

def allowed_audio(filename):
    return '.' in filename and \
           filename.rsplit('.', 1)[1].lower() in ALLOWED_AUDIO_EXTENSIONS

def predict_text(data):
    """Predict emotion from text input and store the result in Firestore."""
    try:
        user_id = data['userID']
        title = data['Term']
        paragraph = data['contentdata']
        
        # Preprocess and predict
        new_data = preprocess_text(title, paragraph)
        prediction = text_model.predict(new_data.astype(str))[0]
        # emotion = emotion_labels[prediction]
        
        # Store in Firestore
        store_prediction(user_id, 'text_emotions', 'text', title, prediction)
        
        return jsonify({'emotion': prediction})
    
    except Exception as e:
        return jsonify({'error': str(e)}), 500

def transcribe_audio(audio_path):
    """Transcribe audio to text using Whisper."""
    try:
        # Load audio for Whisper (16kHz)
        y, sr = librosa.load(audio_path, sr=16000)
        if len(y.shape) > 1:
            y = np.mean(y, axis=1)  # Convert to mono if stereo
        # Transcribe
        result = whisper_model.transcribe(y, language='en')  # Adjust language if needed
        lyrics = result['text'].strip()
        return lyrics if lyrics else "[NO_LYRICS]"
    except Exception as e:
        print(f"Error transcribing {audio_path}: {e}")
        return "[ERROR]"

def convert_to_wav(input_path, input_format):
    """Convert audio file to .wav if not already in .wav format."""
    if input_format.lower() == 'wav':
        return input_path  # No conversion needed for .wav files
    
    wav_path = input_path.rsplit('.', 1)[0] + '.wav'  # Replace extension with .wav
    try:
        audio = AudioSegment.from_file(input_path, format=input_format)
        audio.export(wav_path, format='wav')
        return wav_path
    except Exception as e:
        print(f"Error converting {input_path} to .wav: {e}")
        return None
    finally:
        if 'audio' in locals():
            del audio  # Ensure file is properly closed

def predict_audio(request):
    """Predict emotion from an audio file by transcribing to text and using text emotion detection."""
    temp_dir = tempfile.gettempdir()  # Get system temp directory
    unique_filename = f"{uuid.uuid4().hex}"
    input_ext = None
    input_path = None
    wav_path = None

    try:
        # Validate request
        if 'userID' not in request.form or 'audio' not in request.files:
            return jsonify({'error': 'Missing userID or audio file'}), 400

        user_id = request.form['userID']
        audio_file = request.files['audio']

        # Validate file
        if not allowed_audio(audio_file.filename):
            return jsonify({'error': 'Invalid audio file format'}), 400

        # Get file extension
        input_ext = audio_file.filename.rsplit('.', 1)[1].lower() if '.' in audio_file.filename else ''
        input_path = os.path.join(temp_dir, f"{unique_filename}.{input_ext}")

        # Save the uploaded file
        audio_file.save(input_path)

        # Convert to .wav if not already .wav
        wav_path = convert_to_wav(input_path, input_ext)
        if not wav_path:
            return jsonify({'error': 'Audio conversion failed'}), 500

        # Transcribe audio to text
        transcribed_text = transcribe_audio(wav_path)
        print(transcribed_text)
        if transcribed_text in ["[NO_LYRICS]", "[ERROR]"]:
            print(transcribed_text)
            return jsonify({'error': 'Failed to transcribe audio to meaningful text'}), 500

        # Preprocess transcribed text for text model
        new_data = preprocess_text("", transcribed_text)  # Empty title, transcribed text as content
        prediction = text_model.predict(new_data.astype(str))[0]
        emotion = prediction  # Assuming prediction is the emotion label or index

        # Store in Firestore
        store_prediction(user_id, 'audio_emotions', 'audio', None, emotion)

        return jsonify({'emotion': emotion})

    except Exception as e:
        return jsonify({'error': str(e)}), 500
    finally:
        # Clean up files after response
        for file_path in [input_path, wav_path]:
            if file_path and os.path.exists(file_path) and file_path != input_path:  # Avoid deleting input_path twice
                try:
                    os.remove(file_path)
                except Exception as cleanup_error:
                    print(f"Warning: Could not delete {file_path}: {cleanup_error}")

def predict_bipolar_stage(data):
    """Predict bipolar stage from video, text, audio emotions, and activity level."""
    try:
        user_id = data['userID']
        video_emotion = data['video_emotion']
        text_emotion = data['text_emotion']
        audio_emotion = data['audio_emotion']
        activity = data['activity']

        # Validate inputs
        valid_emotions = ['Happy', 'Sad', 'Surprise', 'Neutral', 'Disgust', 'Angry', 'Fear']
        valid_activities = ['Low', 'Average', 'High']
        if video_emotion not in valid_emotions or text_emotion not in valid_emotions or audio_emotion not in valid_emotions:
            return jsonify({'error': 'Invalid emotion value'}), 400
        if activity not in valid_activities:
            return jsonify({'error': 'Invalid activity value'}), 400

        # Preprocess inputs
        new_data = preprocess_bipolar_inputs(video_emotion, text_emotion, audio_emotion, activity)
        X_new = pd.get_dummies(new_data, columns=new_data.columns)
        X_new = X_new.reindex(columns=bipolar_model.feature_names_in_, fill_value=0)

        # Predict
        prediction = bipolar_model.predict(X_new)
        bipolar_stage = bipolar_label_encoder.inverse_transform(prediction)[0]

        # Store in Firestore
        store_prediction(user_id, 'bipolar_stages', 'bipolar_stage', None, bipolar_stage)

        return jsonify({'bipolar_stage': bipolar_stage})

    except Exception as e:
        return jsonify({'error': str(e)}), 500
