package com.google.sps.servlets;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.gax.rpc.ApiException;
import com.google.gson.Gson;
import com.google.sps.data.VideoAnalysis;
import java.io.IOException;
import java.lang.InterruptedException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.security.GeneralSecurityException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/sentiment")
public class SentimentServlet extends HttpServlet {

  private CommentService commentService;
  private Caption captionService;
  private StoreVideos database;
  private final String INVALID_INPUT_ERROR = "Video is private or does not exist.";
  private final String COMMENTS_FAILED_ERROR = "Comments could not be retrieved.";
  private final String NLP_API_ERROR = "Language service client failed.";
  private final String NO_DATA_ERROR = "No comments or captions available to analyze.";
  private final String READER_ERROR = "Reading video ID failed.";
  private SentimentAnalysis sentimentAnalysis = new SentimentAnalysis();
  private static final int MAX_THREADS = 26; // 25 thread pools for comments, 1 for captions

  public SentimentServlet() throws IOException, GeneralSecurityException {
    captionService = new Caption();
    commentService = new CommentService();
    database = new StoreVideos();
  }

  /**
   * Retrieves the comments and captions of a YouTube video, given its video ID, and calculates the
   * sentiment score for the captions and each individual comment. Then creates a Json String
   * containing the score value.
   * If a video has comments and captions available to analyze, the score returned is the average
   * of both scores. Otherwise, only the score of the available data is returned (not averaged)
   */
  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String videoId = request.getParameter("video-id");

    List<String> commentsList = new ArrayList<>();
    try {
      commentsList = commentService.getCommentsFromId(videoId);
    } catch (IllegalArgumentException e) { // video not found
      System.err.println(e.getMessage());
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, INVALID_INPUT_ERROR);
      return;
    } catch (GoogleJsonResponseException e) { // some other problem with requesting comment data
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, COMMENTS_FAILED_ERROR);
    }

    String captions = captionService.getCaptionFromId(videoId);

    if (commentsList.isEmpty() && captions.isEmpty()) {
      VideoAnalysis videoAnalysis = new VideoAnalysis.Builder()
        .setId(videoId)
        .setScore(null)
        .setScoreAvailable(false)
        .build();
      String json = new Gson().toJson(videoAnalysis);

      response.setContentType("application/json;");
      response.getWriter().println(json);
      return;
    }

    ExecutorService executor = Executors.newFixedThreadPool(MAX_THREADS);

    List<Future<Float>> commentScoreFutures = commentsList.stream()
        .map(comment -> executor.submit(getSentimentScoreCallable(comment)))
        .collect(Collectors.toList());

    Future<Float> captionFuture = executor.submit(getSentimentScoreCallable(captions));

    float commentsScore = 0f;
    float captionsScore = 0f;

    try {
      for (Future<Float> future : commentScoreFutures) {
        commentsScore += future.get();
      }
      commentsScore = commentsScore / commentsList.size();
      captionsScore = captionFuture.get();
    } catch (ExecutionException | InterruptedException e) {
      System.err.println(e.getMessage());
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, NLP_API_ERROR);
      return;
    }

    executor.shutdown();

    float score = determineSentimentScore(commentsList, captions, commentsScore, captionsScore);
    database.addToDatabase(videoId, score);

    VideoAnalysis videoAnalysis = new VideoAnalysis.Builder()
        .setId(videoId)
        .setScore(score)
        .setScoreAvailable(true)
        .build();
    String json = new Gson().toJson(videoAnalysis);

    response.setContentType("application/json;");
    response.getWriter().println(json);
  }

  public Callable<Float> getSentimentScoreCallable(String text) {
    return new Callable<Float>() {
      @Override
      public Float call() {
        return sentimentAnalysis.calculateSentimentScore(text);
      }
    };
  }

  /**
   * Determines the correct sentiment score to return to the client depending on the data available
   * to analyze (comments, captions, or both).
   */
  private float determineSentimentScore(List<String> commentsList, String captions,
      float commentsScore, float captionsScore) {
    float score = 0f;

    if (!commentsList.isEmpty() && !captions.isEmpty()) {
      // Average commentsScore and captionsScore
      score = (commentsScore + captionsScore) / 2f;
    } else if (commentsList.isEmpty() && !captions.isEmpty()) {
      score = captionsScore;
    } else {
      score = commentsScore;
    }

    return score;
  }
}
