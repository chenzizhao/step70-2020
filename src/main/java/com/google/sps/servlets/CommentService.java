package com.google.sps.servlets;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.model.CommentThread;
import com.google.api.services.youtube.model.CommentThreadListResponse;
import com.google.api.services.youtube.YouTube;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;

public class CommentService {

  private final String DEVELOPER_KEY = "DEV_KEY";
  private static final String APPLICATION_NAME = "YouTube Comments";
  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
  private final long MAX_RESULTS = 25L;

  private YouTube getService() throws GeneralSecurityException, IOException {
    final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    return new YouTube.Builder(httpTransport, JSON_FACTORY, null)
        .setApplicationName(APPLICATION_NAME)
        .build();
  }

  /**
   * Calls YouTube Data API to return a list of the top 25 comments for a video, identified by its
   * video id. If the video has no comments or comments can't be retrieved, return an empty list.
   */
  public List<String> getCommentsFromId(String videoId)
      throws GeneralSecurityException, IOException, GoogleJsonResponseException {
    YouTube youtubeService = getService();
    YouTube.CommentThreads.List request = youtubeService.commentThreads()
        .list(Collections.singletonList("snippet"));
    
    CommentThreadListResponse response;
    try { 
      // Call YouTube API and get the 25 comments that appear first in comment section of the video
      response = request.setKey(DEVELOPER_KEY)
          .setMaxResults(MAX_RESULTS)
          .setOrder("relevance")
          .setTextFormat("plainText")
          .setVideoId(videoId)
          .execute();
    } catch (GoogleJsonResponseException e) {
      System.err.println(e.getDetails().getMessage());
      // If comments cannot be retrieved, return an empty list. Comments won't be accounted for in
      // the sentiment score calculation.
      return Arrays.asList();
    }

    List<String> commentsList = new ArrayList<>();
    for (CommentThread commentThread : response.getItems()){
      String commentContent = commentThread.getSnippet()
          .getTopLevelComment()
          .getSnippet()
          .getTextDisplay();
      commentsList.add(commentContent);
    }

    return commentsList;
  }
}