import pandas as pd
import numpy as np
import librosa

def preprocess_text(title, paragraph):
    """Preprocess text data for the model."""
    return pd.DataFrame({'Clean_Title': [title], 'Clean_Paragraph': [paragraph]})

def preprocess_audio(audio_path):
    """Preprocess audio data for the model."""
    x, sr = librosa.load(audio_path)
    mfcc = np.mean(librosa.feature.mfcc(y=x, sr=sr, n_mfcc=128), axis=1)
    return mfcc.reshape(1, 16, 8, 1) 

def preprocess_bipolar_inputs(video_emotion, text_emotion, audio_emotion, activity):
    """Preprocess inputs for bipolar stage prediction."""
    data = pd.DataFrame({
        'video_output': [video_emotion],
        'text_output': [text_emotion],
        'audio_output': [audio_emotion],
        'activity': [activity]
    })
    return data