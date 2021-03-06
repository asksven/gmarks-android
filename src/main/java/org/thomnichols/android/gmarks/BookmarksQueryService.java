/* This file is part of GMarks. Copyright 2010, 2011 Thom Nichols
 *
 * GMarks is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GMarks is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GMarks.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thomnichols.android.gmarks;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.thomnichols.android.gmarks.thirdparty.IOUtils;

import android.net.Uri;
import android.net.http.AndroidHttpClient;
import android.util.Log;

/**
 * @author tnichols
 *
 */
public class BookmarksQueryService {

	public synchronized static BookmarksQueryService getInstance() {
		if ( instance == null ) instance = new BookmarksQueryService(null);
		return instance;
	}
	
	public class AuthException extends IOException {
		private static final long serialVersionUID = 1L;
		public AuthException() { super(); }
		public AuthException( String msg ) { super(msg); }
		public AuthException( Throwable cause) { super(cause); }
		public AuthException( String msg, Throwable cause) { super(msg,cause); }
	}
	
	public class NotFoundException extends IOException {
		private static final long serialVersionUID = 1L;
		public NotFoundException() { super(); }
		public NotFoundException( String msg ) { super(msg); }
		public NotFoundException( Throwable cause) { super(cause); }
		public NotFoundException( String msg, Throwable cause) { super(msg,cause); }
	}
	
	private static BookmarksQueryService instance = null;
	
//	protected DefaultHttpClient http;
	protected AndroidHttpClient http;
	protected HttpContext ctx;
//	protected String USER_AGENT = "";
	protected CookieStore cookieStore;
	protected String TAG = "GMARKS REMOTE SVC";
	protected boolean authInitialized = false;
	protected String xtParam = null;
	protected String mainThreadId = null;
	
	private BookmarksQueryService( String userAgent ) {
//		java.util.logging.Logger.getLogger("httpclient.wire.header").setLevel(java.util.logging.Level.FINEST);
//		java.util.logging.Logger.getLogger("httpclient.wire.content").setLevel(java.util.logging.Level.FINEST);
		ctx = new BasicHttpContext();
		cookieStore = new BasicCookieStore();
		ctx.setAttribute(ClientContext.COOKIE_STORE, cookieStore);
		String defaultUA = "Mozilla/5.0 (Linux; U; Android 2.1; en-us) AppleWebKit/522+ (KHTML, like Gecko) Safari/419.3";
//		http = new DefaultHttpClient();
		http = AndroidHttpClient.newInstance( userAgent != null ? userAgent : defaultUA );
	}
	
	public void setAuthCookies( List<Cookie> cookies ) {
		this.cookieStore.clear();
		for ( Cookie c : cookies ) this.cookieStore.addCookie(c);
		// TODO if cookies are expired, don't set them & notify caller they should be refreshed.
		this.authInitialized = true;
	}
	
	public boolean isAuthInitialized() {
		return authInitialized;
	}
	
	public void clearAuthCookies() {
		this.cookieStore.clear();
		this.authInitialized = false;
	}
	
	public void login( String user, String passwd ) {
		try {
			List<NameValuePair> queryParams = new ArrayList<NameValuePair>();
			queryParams.add( new BasicNameValuePair("service", "bookmarks") );
			queryParams.add( new BasicNameValuePair("passive", "true") );
			queryParams.add( new BasicNameValuePair("nui", "1") );
			queryParams.add( new BasicNameValuePair("continue", "https://www.google.com/bookmarks/l") );
			queryParams.add( new BasicNameValuePair("followup", "https://www.google.com/bookmarks/l") );
			HttpGet get = new HttpGet( "https://www.google.com/accounts/ServiceLogin?" + 
					URLEncodedUtils.format(queryParams, "UTF-8") );
			HttpResponse resp = http.execute(get, this.ctx);
			// this just gets the cookie but I can ignore it...
			
			if ( resp.getStatusLine().getStatusCode() != 200 )
				throw new RuntimeException( "Invalid status code for ServiceLogin " +
						resp.getStatusLine().getStatusCode() );
			resp.getEntity().consumeContent();
			
			String galx = null;
			for ( Cookie c : cookieStore.getCookies() )
				if ( c.getName().equals( "GALX" ) ) galx = c.getValue(); 
			
			if ( galx == null ) throw new RuntimeException( "GALX cookie not found!" );
			
			HttpPost loginMethod = new HttpPost("https://www.google.com/accounts/ServiceLoginAuth");
			// post parameters:
			List<NameValuePair> nvps = new ArrayList<NameValuePair>();
			nvps.add(new BasicNameValuePair("Email", user));
			nvps.add(new BasicNameValuePair("Passwd", passwd));
			nvps.add(new BasicNameValuePair("PersistentCookie", "yes"));
			nvps.add(new BasicNameValuePair("GALX", galx));			
			nvps.add(new BasicNameValuePair("continue", "https://www.google.com/bookmarks/l"));
			loginMethod.setEntity(new UrlEncodedFormEntity(nvps));
			resp = http.execute( loginMethod, this.ctx );
			
			if ( resp.getStatusLine().getStatusCode() != 302 )
				throw new RuntimeException( "Unexpected status code for ServiceLoginAuth" +
						resp.getStatusLine().getStatusCode() );
			resp.getEntity().consumeContent();
			
			Header checkCookieLocation = resp.getFirstHeader("Location");
			if ( checkCookieLocation == null ) 
				throw new RuntimeException("Missing checkCookie redirect location!");

			// CheckCookie:
			get = new HttpGet( checkCookieLocation.getValue() );
			resp = http.execute( get, this.ctx );
			
			if ( resp.getStatusLine().getStatusCode() != 302 )
				throw new RuntimeException( "Unexpected status code for CheckCookie" +
						resp.getStatusLine().getStatusCode() );
			resp.getEntity().consumeContent();
			
			this.authInitialized = true;
			Log.i(TAG, "Final redirect location: " + resp.getFirstHeader("Location").getValue() );
			Log.i(TAG, "Logged in.");
		}
		catch ( IOException ex ) {
			Log.e(TAG, "Error during login", ex );
			throw new RuntimeException("IOException during login", ex);
		}
	}
	
	public boolean testAuth() {
		HttpGet get = new HttpGet( "https://www.google.com/bookmarks/api/threadsearch?fo=Starred&g&q&start&nr=1" );
		try {
			HttpResponse resp = http.execute( get, this.ctx );
			int statusCode = resp.getStatusLine().getStatusCode();
			Log.d( TAG, "testAuth return code: " + statusCode );
			return statusCode < 400;
		}
		catch ( IOException ex ) {
			Log.e( TAG, "Error while checking auth status", ex );
		}
		return false;
	}

	public Bookmark create( Bookmark b ) throws IOException {
		final String createURL = "https://www.google.com/bookmarks/api/thread?op=Star"
			+ "&xt=" + URLEncoder.encode( getXtParam(), "UTF-8" );
		
//td {"results":[{"threadId":"BDQAAAAAQAA","elementId":0,"authorId":0,
//                "title":"My Blog","timestamp":0,"formattedTimestamp":0,
//                "url":"http://blog.thomnichols.org","signedUrl":"",
//                "previewUrl":"","snippet":"___________","threadComments":[],
//                "parentId":"BDQAAAAAQAA","labels":["mobile"]}]}
		JSONObject requestObj = new JSONObject();
		try {
			JSONObject bookmarkObj = new JSONObject();
			// TODO this is part of a bookmark but I've been ignoring it...
			bookmarkObj.put("threadId", this.mainThreadId);
			bookmarkObj.put( "elementId", 0);
			bookmarkObj.put( "title", b.getTitle() );
			bookmarkObj.put( "url", b.getUrl() );
			bookmarkObj.put( "snippet", b.getDescription() );
			JSONArray labels = new JSONArray();
			for (String label : b.getLabels() ) labels.put(label);
			bookmarkObj.put( "labels", labels );
			
			bookmarkObj.put( "timestamp", 0 );
			bookmarkObj.put( "formattedTimestamp", 0 );
			bookmarkObj.put( "authorId", 0 );
			bookmarkObj.put( "signedUrl", "" );
			bookmarkObj.put( "previewUrl", "" );
			bookmarkObj.put( "threadComments", new JSONArray() );
			// this is the same as threadId...  Do I need to know the value for this??
			bookmarkObj.put( "parentId", this.mainThreadId );

			JSONArray resultArray = new JSONArray();
			resultArray.put(bookmarkObj);
			requestObj.put("results", resultArray);			
		}
		catch ( JSONException ex ) {
			throw new IOException( "Error creating request", ex );
		}

		return createOrUpdate( createURL, requestObj );
	}
	
	public Bookmark update( Bookmark b ) throws IOException {
		String updateURL = "https://www.google.com/bookmarks/api/thread?op=UpdateThreadElement" 
			+ "&xt=" + URLEncoder.encode( getXtParam(), "UTF-8" );
		
		JSONObject requestObj = new JSONObject();
		try {
			JSONObject bookmarkObj = new JSONObject();
			// TODO this is part of a bookmark but I've been ignoring it...
			bookmarkObj.put("threadId", b.getThreadId());
			bookmarkObj.put( "elementId", b.getGoogleId());
			bookmarkObj.put( "title", b.getTitle() );
			bookmarkObj.put( "url", b.getUrl() );
			bookmarkObj.put( "snippet", b.getDescription() );
			JSONArray labels = new JSONArray();
			for (String label : b.getLabels() ) labels.put(label);
			bookmarkObj.put( "labels", labels );
			
// these are in the request but empty... maybe we can ignore them???
//			"authorId":0,"timestamp":0,"formattedTimestamp":0,"signedUrl":"",
//			"previewUrl":"","threadComments":[],"parentId":"",
			bookmarkObj.put( "authorId", 0 );
			bookmarkObj.put( "timestamp", 0 );
			bookmarkObj.put( "formattedTimestamp", 0 );
			bookmarkObj.put( "signedUrl", "" );
			bookmarkObj.put( "previewUrl", "" );
			bookmarkObj.put( "threadComments", new JSONArray() );
			bookmarkObj.put( "parentId", "" );

			JSONArray results = new JSONArray();
			results.put(bookmarkObj);
			requestObj.put("threadResults", results);
			
			// other unneeded params that are part of an update request:
			JSONArray emptyArray = new JSONArray();
			requestObj.put("threads", emptyArray);
			requestObj.put("threadQueries", emptyArray);
			requestObj.put("threadComments", emptyArray);
			
		}
		catch ( JSONException ex ) {
			throw new IOException( "Error creating request", ex );
		}

		Bookmark newBookmark = createOrUpdate( updateURL, requestObj );
		if ( b.get_id() != null ) newBookmark.set_id(b.get_id());
		// TODO created date is not in response
//		if ( b.getCreatedDate() != 0 ) newBookmark.setCreatedDate(b.getCreatedDate());
		return newBookmark;
	}

	public void delete(String googleId) throws AuthException, NotFoundException, IOException {
		Uri requestURI = Uri.parse("https://www.google.com/bookmarks/api/thread").buildUpon()
			.appendQueryParameter("xt", getXtParam() )
			.appendQueryParameter("op", "DeleteItems").build();
//		final String deleteURL = "https://www.google.com/bookmarks/api/thread?"
//			+ "xt=" + URLEncoder.encode( getXtParam(), "UTF-8" )  
//			+ "&op=DeleteItems";
		//  td	{"deleteAllBookmarks":false,"deleteAllThreads":false,"urls":[],"ids":["____"]}

		JSONObject requestObj = new JSONObject();
		try {
			requestObj.put("deleteAllBookmarks", false);
			requestObj.put("deleteAllThreads", false);
			requestObj.put("urls", new JSONArray());
			JSONArray elementIDs = new JSONArray();
			elementIDs.put( googleId );
			requestObj.put("ids", elementIDs);
		}
		catch ( JSONException ex ) {
			throw new IOException( "JSON error while creating request" );
		}
		
		String postString = "{\"deleteAllBookmarks\":false,\"deleteAllThreads\":false,\"urls\":[],\"ids\":[\""
			+ googleId + "\"]}";
		
		List<NameValuePair> params = new ArrayList<NameValuePair>();
//		params.add( new BasicNameValuePair("td", requestObj.toString()) );
		params.add( new BasicNameValuePair("td", postString) );

//		Log.v(TAG,"DELETE: " + requestObj.toString());
		Log.v(TAG,"DELETE: " + requestURI );
		Log.v(TAG,"DELETE: " + postString);

		HttpPost post = new HttpPost( requestURI.toString() );		
//		HttpPost post = new HttpPost( deleteURL );		
		post.setEntity( new UrlEncodedFormEntity(params) );
		HttpResponse resp = http.execute( post, this.ctx );
		
		int respCode = resp.getStatusLine().getStatusCode();
		if ( respCode == 401 ) throw new AuthException();
		if ( respCode > 299 ) 
			throw new IOException( "Unexpected response code: " + respCode );

		try { // always assume a single item is created or updated.
			JSONObject respObj = parseJSON(resp);
			int deletedCount = respObj.getInt("numDeletedBookmarks");
			
			if ( deletedCount < 1 )
				throw new NotFoundException( "Bookmark could not be found; " + googleId );
			if ( deletedCount > 1 )
				throw new IOException("Expected 1 deleted bookmark but got " + deletedCount );
		} 
		catch ( JSONException ex ) {
			throw new IOException( "Response parse error", ex );
		}
	}
	
	protected Bookmark createOrUpdate( String url, JSONObject requestObj ) throws AuthException, IOException {
		HttpPost post = new HttpPost( url );
		
//		Log.v(TAG, "UPDATE: " + url);
//		Log.v(TAG, "UPDATE: " + requestObj);
		List<NameValuePair> params = new ArrayList<NameValuePair>();
		params.add( new BasicNameValuePair("td", requestObj.toString()) );
		post.setEntity( new UrlEncodedFormEntity(params, "UTF-8") );
		HttpResponse resp = http.execute( post, this.ctx );
		
		int respCode = resp.getStatusLine().getStatusCode(); 
		if ( respCode == 401 ) throw new AuthException();
		if ( respCode > 299 ) 
			throw new IOException( "Unexpected response code: " + respCode );

		try { // always assume a single item is created or updated.
			JSONObject respObj = parseJSON(resp);
			if ( respObj.has("results") ) // create response:
				respObj = respObj.getJSONArray("results").getJSONObject(0).getJSONObject("threadresult");
			else respObj = respObj.getJSONArray("threadResults").getJSONObject(0);
			Bookmark b = new Bookmark( respObj.getString("elementId"),
					respObj.getString("threadId"),
					respObj.getString("title"),
					respObj.getString("url"),
					respObj.getString("host"),
					respObj.getString("snippet"),
					-1, // no created date in response
					respObj.getLong("timestamp") );
			
			if ( respObj.has("faviconUrl") ) b.setFaviconURL(respObj.getString("faviconUrl"));
			
//			Log.v(TAG, "RESPONSE: " + respObj );
			if ( respObj.has("labels") ) {
				JSONArray labelJSON = respObj.getJSONArray("labels");
				
				for ( int i=0; i< labelJSON.length(); i++ )
					b.getLabels().add(labelJSON.getString(i));
			}
			
			return b;
		} 
		catch ( JSONException ex ) {
			Log.w(TAG, "Response parse error", ex );
			throw new IOException( "Response parse error" );
		}
	}
	
	protected String getXtParam() throws AuthException, IOException {
		if ( this.xtParam != null ) return this.xtParam; // already init'd
		
		HttpGet get = new HttpGet("https://www.google.com/bookmarks/l");
		
		HttpResponse resp = http.execute(get, this.ctx);
		
		final int responseCode = resp.getStatusLine().getStatusCode();
		if ( responseCode == 401 ) 
			throw new AuthException("Please log in");
		if (  responseCode != 200 ) 
			throw new IOException( "Unexpected response code: " + responseCode );
		
		// TODO encoding
		final String respString = IOUtils.toString( resp.getEntity().getContent() );
		
		final String xtSearchString = ";SL.xt = '";
		int startIndex = respString.indexOf(xtSearchString);
		if ( startIndex < 0 ) throw new IOException("Could not find xtSearchString");
		startIndex += xtSearchString.length(); 
		this.xtParam = respString.substring( startIndex, 
				respString.indexOf("'", startIndex) );
//		Log.d(TAG, "XT context: " + respString.substring( startIndex-10, 
//				respString.indexOf("'", startIndex)+5 ) );
		Log.d(TAG, "GOT XT PARAM: " + xtParam );
		
		// Get main thread ID:
		final String mainThreadSearchString = "(a.threadID):\"";
		startIndex = respString.indexOf(mainThreadSearchString);
		if ( startIndex < 0 ) throw new IOException("Could not find thread ID");
		startIndex += mainThreadSearchString.length(); 
		this.mainThreadId = respString.substring( startIndex, 
				respString.indexOf("\"", startIndex) );
		Log.d(TAG, "GOT THREAD ID: " + mainThreadId );
		
		return this.xtParam;
	}
	
	protected JSONObject queryJSON(String uri) throws AuthException, JSONException, IOException {
		HttpGet get = new HttpGet(uri);

		HttpResponse resp = http.execute( get, this.ctx );
		int code = resp.getStatusLine().getStatusCode();
		if ( code == 401 || code == 403 ) {
			Log.d(TAG, "Auth failure from queryJSON");
			throw new AuthException(); 
		}
		if ( code != 200 ) {
			Log.e( TAG, "Unexpected response code: " + code );
			throw new IOException("Unexpected response code: " + code );
		}
		return parseJSON( resp );
	}
	
	protected JSONObject parseJSON( HttpResponse resp ) throws IOException, JSONException {
		String charset = null;
		try {
			charset = resp.getEntity().getContentType().getElements()[0]
			                     .getParameterByName("charset").getValue();			
		}
		catch ( Exception ex ) { charset = "UTF-8"; }
		String respData = IOUtils.toString( resp.getEntity().getContent(), charset );
		resp.getEntity().consumeContent();
		if ( respData.startsWith(")]}'") )
			respData = respData.substring( respData.indexOf("\n") );
		
		JSONTokener parser = new JSONTokener(respData);
		return (JSONObject) parser.nextValue();
	}
	
	public List<Label> getLabels() throws IOException {
		String uri = "https://www.google.com/bookmarks/api/bookmark?op=LIST_LABELS";
		
		try {
			JSONObject labelsObj = queryJSON(uri);
			JSONArray labels = labelsObj.getJSONArray("labels");
			JSONArray counts = labelsObj.getJSONArray("counts");
			
			ArrayList<Label> list = new ArrayList<Label>();
			for ( int i=0; i< labels.length(); i++ )
				list.add( new Label(labels.getString(i), counts.getInt(i)) );
			
			return list;
		}
		catch ( JSONException ex ) {
			Log.e(TAG, "Labels query JSON parse exception", ex );
			throw new IOException("Error retrieving labels", ex );
		}
	}
	
	public Iterable<BookmarkList> getMyBookmarks() throws AuthException, IOException {
		return new BookmarkListIterator(this, BookmarkList.LISTS_PRIVATE);
	}
	public Iterable<BookmarkList> getSharedBookmarks() throws AuthException, IOException {
		return new BookmarkListIterator(this, BookmarkList.LISTS_SHARED);
	}
	public Iterable<BookmarkList> getPublishedBookmarks() throws AuthException, IOException {
		return new BookmarkListIterator(this, BookmarkList.LISTS_PUBLIC);
	}

	/**
	 * This has the potential to be relatively large..
	 */
	public Iterable<Bookmark> getAllBookmarks() throws AuthException, IOException {
		// make a sequence of JSON requests until they've all been retrieved.
		// max 25 bookmarks can be requested at one time.
		return new AllBookmarksIterator(this);
	}
}
