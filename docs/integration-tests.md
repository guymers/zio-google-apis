
Ensure Google default credentials are available by running `gcloud auth application-default login`.

Set the environment variables `ZGA_TEST_GOOGLE_DEFAULT_CREDENTIALS_AVAILABLE` and `ZGA_TEST_GOOGLE_PROJECT_ID`. 

The Google Cloud project must have a Cloud Run Job called `zga-test-job` defined in `us-west1`.

The Secret Manager integration test creates a secret called `ZGA_TEST` if it does not already exist and adds a version to it.

The Storage integration tests run only when the environment variable `ZGA_TEST_GOOGLE_STORAGE_BUCKET` is set to the name of an existing bucket. They write objects under `zga-test/` in that bucket and delete them afterwards.
