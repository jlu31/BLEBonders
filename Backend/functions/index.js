const { onRequest } = require("firebase-functions/v2/https");
const { logger } = require("firebase-functions");
const admin = require("firebase-admin");
const AWS = require("aws-sdk");

admin.initializeApp();

exports.getUploadUrl = onRequest({ region: "us-central1", cors: true }, async (req, res) => {
  try {
    if (req.method !== "GET") return res.status(405).send("Use GET");

    // Read ONLY from environment variables
    const accessKeyId = process.env.AWS_ACCESS_KEY_ID;
    const secretAccessKey = process.env.AWS_SECRET_ACCESS_KEY;
    const bucket = process.env.S3_BUCKET || "bond-audio-uploads";
    const region = process.env.AWS_REGION || "us-east-1";

    if (!accessKeyId || !secretAccessKey) {
      logger.error("Missing AWS credentials");
      return res.status(500).send("Missing AWS credentials");
    }

    const s3 = new AWS.S3({
      accessKeyId,
      secretAccessKey,
      region,
      signatureVersion: "v4",
    });

    const fileName = req.query.fileName || `audio_${Date.now()}.mp3`;
    const fileType = req.query.fileType || "audio/mpeg";

    const params = { Bucket: bucket, Key: fileName, Expires: 60 * 5, ContentType: fileType };
    const uploadURL = await s3.getSignedUrlPromise("putObject", params);

    res.status(200).json({ uploadURL, fileName, bucket, region });
  } catch (err) {
    logger.error("Error generating upload URL", err);
    res.status(500).send("Failed to generate upload URL");
  }
});
