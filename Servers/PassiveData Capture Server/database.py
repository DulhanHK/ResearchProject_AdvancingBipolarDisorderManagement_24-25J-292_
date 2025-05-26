import firebase_admin
from firebase_admin import credentials, firestore

# Initialize Firebase
cred = credentials.Certificate("serviceaccountkey.json")
firebase_admin.initialize_app(cred)
db = firestore.client()

def store_prediction(user_id, collection_name, prediction_type, input_data, emotion):
    """Store prediction result in Firestore."""
    doc_ref = db.collection('users').document(user_id).collection(collection_name).document()
    doc_ref.set({
        'user_id': user_id,
        'type': prediction_type,
        'input': input_data if prediction_type == 'text' else None,
        'emotion': emotion,
        'timestamp': firestore.SERVER_TIMESTAMP
    })