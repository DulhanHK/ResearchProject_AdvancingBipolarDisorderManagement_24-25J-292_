import joblib
# from tensorflow.keras.models import load_model

def load_text_model():
    """Load the text emotion detection model."""
    return joblib.load("weights/text_emotion/emotion_classifier_logistic.pkl")

# def load_audio_model():
#     """Load the audio emotion detection model."""
#     return load_model("weights/audio_emotion/model.h5")

def load_bipolar_model():
    """Load the bipolar stage prediction model and label encoder."""
    model = joblib.load("weights/bipolar_stage/bipolar_model.pkl")
    label_encoder = joblib.load("weights/bipolar_stage/label_encoder.pkl")
    return model, label_encoder