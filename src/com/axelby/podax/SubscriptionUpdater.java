package com.axelby.podax;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Vector;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Xml;

class SubscriptionUpdater {
	private static final String NAMESPACE_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd";
	private static final String NAMESPACE_MEDIA = "http://search.yahoo.com/mrss/";

	private Context _context;
	Vector<Integer> _toUpdate = new Vector<Integer>();
	Thread _runningThread;
	
	public static final SimpleDateFormat rfc822DateFormats[] = new SimpleDateFormat[] {
			new SimpleDateFormat("EEE, d MMM yy HH:mm:ss z"),
			new SimpleDateFormat("EEE, d MMM yy HH:mm z"),
			new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z"),
			new SimpleDateFormat("EEE, d MMM yyyy HH:mm z"),
			new SimpleDateFormat("d MMM yy HH:mm z"),
			new SimpleDateFormat("d MMM yy HH:mm:ss z"),
			new SimpleDateFormat("d MMM yyyy HH:mm z"),
			new SimpleDateFormat("d MMM yyyy HH:mm:ss z"), };
	public static SimpleDateFormat rssDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z");

	SubscriptionUpdater(Context context) {
		_context = context;
	}
	
	public void addSubscriptionId(int id) {
		_toUpdate.add(id);
	}
	
	public void addAllSubscriptions() {
		String[] projection = new String[] { SubscriptionProvider.COLUMN_ID };
		Cursor c = _context.getContentResolver().query(SubscriptionProvider.URI, projection, null, null, null);
		while (c.moveToNext())
			_toUpdate.add((int)(long)c.getLong(0));
		c.close();
	}
	
	public void run() {
		if (_runningThread != null)
			return;
		_runningThread = new Thread(_worker);
		_runningThread.start();
	}
	
	private Runnable _worker = new Runnable() {
		public void run() {
			try {
				if (!Helper.ensureWifi(_context))
					return;

				// send subscriptions to the Podax server
				// errors are acceptible
				try {
					sendSubscriptionsToPodaxServer();
				} catch (Exception ex) {
					ex.printStackTrace();
				}

				while (_toUpdate.size() > 0) {
					Integer subscriptionId = _toUpdate.get(0);
					_toUpdate.remove(0);
					Uri subscriptionUri = ContentUris.withAppendedId(SubscriptionProvider.URI, subscriptionId);
					String[] projection = new String[] {
							SubscriptionProvider.COLUMN_ID,
							SubscriptionProvider.COLUMN_TITLE,
							SubscriptionProvider.COLUMN_URL,
							SubscriptionProvider.COLUMN_ETAG,
							SubscriptionProvider.COLUMN_LAST_MODIFIED,
					};

					ContentValues subscriptionValues = new ContentValues();

					Cursor c = _context.getContentResolver().query(subscriptionUri, projection, null, null, null);
					try {
						if (!c.moveToNext())
							continue;
						SubscriptionCursor subscription = new SubscriptionCursor(_context, c);

						HttpGet request = new HttpGet(subscription.getUrl());
						if (subscription.getETag() != null)
							request.addHeader("If-None-Match", subscription.getETag());
						if (subscription.getLastModified() != null && subscription.getLastModified().getTime() > 0) {
							SimpleDateFormat imsFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
							request.addHeader("If-Modified-Since", imsFormat.format(subscription.getLastModified()));
						}
						HttpClient client = new DefaultHttpClient();
						HttpResponse response = client.execute(request);

						int code = response.getStatusLine().getStatusCode();
						// check for content not modified
						if (code == 304) {
							continue;
						}

						String eTag = null;
						if (response.containsHeader("ETag")) {
							eTag = response.getLastHeader("ETag").getValue();
							subscriptionValues.put(SubscriptionProvider.COLUMN_ETAG, eTag);
							if (eTag.equals(subscription.getETag())) {
								continue;
							}
						}

						updateUpdateNotification(subscription, "Downloading Feed");
						InputStream responseStream = response.getEntity().getContent();
						if (responseStream == null) {
							showUpdateErrorNotification(subscription, "Feed not available. Check the URL.");
							continue;
						}

						try {
							XmlPullParser parser = Xml.newPullParser();
							//FIXME: Workaround, weil Encoding detection nicht funktioniert
							String encoding = null;
							Log.i("Podax", "Testing URI" + subscription.getUrl());
							if (new URL(subscription.getUrl()).getHost().equals ("www.dradio.de")) {
								encoding = "ISO-8859-1";
								Log.w("Podax", "DRadio URI found, set encoding");
							}
							parser.setInput(responseStream, encoding);
							processSubscriptionXML(subscriptionId, subscriptionValues, parser);
							// parseSubscriptionXML stops when it finds an item tag
							processPodcastXML(subscriptionId, subscription, parser);
						} catch (XmlPullParserException e) {
							// not much we can do about this
							Log.w("Podax", "error in subscription xml: " + e.getMessage());
							showUpdateErrorNotification(subscription, _context.getString(R.string.rss_not_valid));
						}

						updateUpdateNotification(subscription, "Saving...");
					} catch (Exception e) {
						Log.w("Podax", "error while updating: " + e.getMessage());
					} finally {
						c.close();
					}

					// finish grabbing subscription values and update
					subscriptionValues.put(SubscriptionProvider.COLUMN_LAST_UPDATE, new Date().getTime() / 1000);
					_context.getContentResolver().update(subscriptionUri, subscriptionValues, null, null);
				}

				writeSubscriptionOPML();
			} catch (Exception e) {
				Log.w("Podax", "error in outer loop: " + e.getMessage());
			} finally {
				removeUpdateNotification();
				_runningThread = null;
				UpdateService.downloadPodcasts(_context);
			}
		}

		public void sendSubscriptionsToPodaxServer()
				throws MalformedURLException, IOException, ProtocolException {
			if (PreferenceManager.getDefaultSharedPreferences(_context).getBoolean("usageDataPref", true) == false)
				return;

			URL podaxServer = new URL("http://www.axelby.com/podax.php");
			HttpURLConnection podaxConn = (HttpURLConnection)podaxServer.openConnection();
			podaxConn.setRequestMethod("POST");
			podaxConn.setDoOutput(true);
			podaxConn.setDoInput(true);
			podaxConn.connect();
			
			String[] projection = new String[] {
					SubscriptionProvider.COLUMN_URL,
			};
			Cursor c = _context.getContentResolver().query(SubscriptionProvider.URI, projection, null, null, null);

			OutputStreamWriter wr = new OutputStreamWriter(podaxConn.getOutputStream());
			wr.write("inst=");
			wr.write(Installation.id(_context));
			while (c.moveToNext()) {
				SubscriptionCursor subscription = new SubscriptionCursor(_context, c);
				String url = subscription.getUrl();
				wr.write("&sub[");
				wr.write(String.valueOf(c.getPosition()));
				wr.write("]=");
				wr.write(URLEncoder.encode(url));
			}
			wr.flush();

			c.close();

			BufferedReader rd = new BufferedReader(new InputStreamReader(podaxConn.getInputStream()));
			Vector<String> response = new Vector<String>();
			String line;
			while ((line = rd.readLine()) != null)
				response.add(line);
			if (response.size() > 1 || !response.get(0).equals("OK")) {
				Log.w("Podax", "Podax server error");
				Log.w("Podax", "------------------");
				for (String s : response)
					Log.w("Podax", s);
			}
		}
	};

	private void showUpdateErrorNotification(SubscriptionCursor subscription, String reason) {
		int icon = R.drawable.icon;
		CharSequence tickerText = "Error Updating Subscription";
		long when = System.currentTimeMillis();			
		Notification notification = new Notification(icon, tickerText, when);
		
		CharSequence contentTitle = "Error updating " + subscription.getTitle();
		CharSequence contentText = reason;
		Intent notificationIntent = new Intent(_context, SubscriptionListActivity.class);
		PendingIntent contentIntent = PendingIntent.getActivity(_context, 0, notificationIntent, 0);
		notification.setLatestEventInfo(_context, contentTitle, contentText, contentIntent);
		
		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager notificationManager = (NotificationManager) _context.getSystemService(ns);
		notificationManager.notify(Constants.SUBSCRIPTION_UPDATE_ERROR, notification);
	}
	

	protected void writeSubscriptionOPML() {
		try {
			File file = new File(_context.getExternalFilesDir(null), "podax.opml");
			FileOutputStream output = new FileOutputStream(file);
			XmlSerializer serializer = Xml.newSerializer();

			serializer.setOutput(output, "UTF-8");
			serializer.startDocument("UTF-8", true);
			serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

			serializer.startTag(null, "opml");
			serializer.attribute(null, "version", "1.0");

			serializer.startTag(null, "head");
			serializer.startTag(null, "title");
			serializer.text("Podax Subscriptions");
			serializer.endTag(null, "title");
			serializer.endTag(null, "head");

			serializer.startTag(null, "body");

			String[] projection = {
					SubscriptionProvider.COLUMN_TITLE,
					SubscriptionProvider.COLUMN_URL,
			};
			Cursor c = _context.getContentResolver().query(SubscriptionProvider.URI, projection , null, null, SubscriptionProvider.COLUMN_TITLE);
			while (c.moveToNext()) {
				SubscriptionCursor sub = new SubscriptionCursor(_context, c);

				serializer.startTag(null, "outline");
				serializer.attribute(null, "title", sub.getTitle());
				serializer.attribute(null, "xmlUrl", sub.getUrl());
				serializer.endTag(null, "outline");
			}

			serializer.endTag(null, "body");
			serializer.endTag(null, "opml");

			serializer.endDocument();
			output.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void updateUpdateNotification(SubscriptionCursor subscription, String status) {
		int icon = R.drawable.icon;
		CharSequence tickerText = "Updating Subscriptions";
		long when = System.currentTimeMillis();			
		Notification notification = new Notification(icon, tickerText, when);
		
		CharSequence contentTitle = "Updating " + subscription.getTitle();
		CharSequence contentText = status;
		Intent notificationIntent = new Intent(_context, SubscriptionUpdater.class);
		PendingIntent contentIntent = PendingIntent.getActivity(_context, 0, notificationIntent, 0);
		notification.setLatestEventInfo(_context, contentTitle, contentText, contentIntent);
		notification.flags |= Notification.FLAG_ONGOING_EVENT;
		
		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager notificationManager = (NotificationManager) _context.getSystemService(ns);
		notificationManager.notify(Constants.SUBSCRIPTION_UPDATE_ONGOING, notification);
	}
	
	private void removeUpdateNotification() {
		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager notificationManager = (NotificationManager) _context.getSystemService(ns);
		notificationManager.cancel(Constants.SUBSCRIPTION_UPDATE_ONGOING);
	}

	private SimpleDateFormat findMatchingDateFormat(SimpleDateFormat[] choices, String date) {
		for (SimpleDateFormat format : choices) {
			try {
				format.parse(date);
				return format;
			} catch (ParseException e) {
			}
		}

		// try it again in english
		for (SimpleDateFormat format : choices) {
			try {
				SimpleDateFormat enUSFormat = new SimpleDateFormat(format.toPattern(), Locale.US);
				enUSFormat.parse(date);
				return enUSFormat;
			} catch (ParseException e) {
			}
		}
		return null;
	}

	private void processPodcastXML(Integer subscriptionId, SubscriptionCursor subscription, XmlPullParser parser)
			throws XmlPullParserException, IOException, ParseException {
		// grab podcasts from item tags
		ContentValues podcastValues = null;
		for (int eventType = parser.getEventType(); eventType != XmlPullParser.END_DOCUMENT; eventType = parser.next()) {
			if (eventType == XmlPullParser.START_TAG) {
				String name = parser.getName();
				String namespace = parser.getNamespace();
				if (name.equalsIgnoreCase("item")) {
					podcastValues = new ContentValues();
					podcastValues.put(PodcastProvider.COLUMN_SUBSCRIPTION_ID, subscriptionId);
				} else if (name.equalsIgnoreCase("title") && parser.getNamespace().equals("")) {
					String text = parser.nextText();
					podcastValues.put(PodcastProvider.COLUMN_TITLE, text);
				} else if (name.equalsIgnoreCase("link")) {
					podcastValues.put(PodcastProvider.COLUMN_LINK, parser.nextText());
				} else if (namespace.equals("") && name.equalsIgnoreCase("description")) {
					podcastValues.put(PodcastProvider.COLUMN_DESCRIPTION, parser.nextText());
				} else if (name.equalsIgnoreCase("pubDate")) {
					String dateText = parser.nextText();
					SimpleDateFormat format = findMatchingDateFormat(rfc822DateFormats, dateText);
					Date pubDate = format.parse(dateText);
					podcastValues.put(PodcastProvider.COLUMN_PUB_DATE, pubDate.getTime() / 1000);
				} else if (name.equalsIgnoreCase("enclosure")) {
					podcastValues.put(PodcastProvider.COLUMN_MEDIA_URL, parser.getAttributeValue(null, "url"));
					String length = parser.getAttributeValue(null, "length");
					try {
						podcastValues.put(PodcastProvider.COLUMN_FILE_SIZE, Long.valueOf(length));
					} catch (Exception e) {
						podcastValues.put(PodcastProvider.COLUMN_FILE_SIZE, 0L);
					}
				}
			} else if (eventType == XmlPullParser.END_TAG) {
				String name = parser.getName();
				if (name.equalsIgnoreCase("item")) {
					String mediaUrl = podcastValues.getAsString(PodcastProvider.COLUMN_MEDIA_URL);
					if (mediaUrl != null && mediaUrl.length() > 0)
						_context.getContentResolver().insert(PodcastProvider.URI, podcastValues);
					podcastValues = null;
				}
			}
		}
	}

	private void downloadThumbnail(long subscriptionId, String thumbnailUrl) {
		File thumbnailFile = new File(SubscriptionProvider.getThumbnailFilename(subscriptionId));
		if (thumbnailFile.exists())
			return;

		InputStream thumbIn;
		try {
			thumbIn = new URL(thumbnailUrl).openStream();
			FileOutputStream thumbOut = new FileOutputStream(thumbnailFile.getAbsolutePath());
			byte[] buffer = new byte[1024];
			int bufferLength = 0;
			while ( (bufferLength = thumbIn.read(buffer)) > 0 )
				thumbOut.write(buffer, 0, bufferLength);
			thumbOut.close();
			thumbIn.close();
		} catch (IOException e) {
			if (thumbnailFile.exists())
				thumbnailFile.delete();
		}
	}

	private void processSubscriptionXML(Integer subscriptionId,
			ContentValues subscriptionValues, XmlPullParser parser)
			throws XmlPullParserException, IOException, ParseException {
		// look for subscription details, stop at item tag
		for (int eventType = parser.getEventType();
				eventType != XmlPullParser.END_DOCUMENT;
				eventType = parser.next()) {
			if (eventType != XmlPullParser.START_TAG)
				continue;

			String name = parser.getName();
			// if we're starting an item, move past the subscription details section
			if (name.equals("item")) {
				break;
			} else if (name.equalsIgnoreCase("lastBuildDate")) {
				String date = parser.nextText();
				SimpleDateFormat format = findMatchingDateFormat(rfc822DateFormats, date);
				if (format != null) {
					Date lastBuildDate = format.parse(date);
					subscriptionValues.put(SubscriptionProvider.COLUMN_LAST_MODIFIED, lastBuildDate.getTime() / 1000);
				}
			} else if (name.equalsIgnoreCase("title") && parser.getNamespace().equals("")) {
				subscriptionValues.put(SubscriptionProvider.COLUMN_TITLE, parser.nextText());
			} else if (name.equalsIgnoreCase("thumbnail") &&
					parser.getNamespace() == NAMESPACE_MEDIA) {
				String thumbnail = parser.getAttributeValue(null, "url");
				subscriptionValues.put(SubscriptionProvider.COLUMN_THUMBNAIL, thumbnail);
				downloadThumbnail(subscriptionId, thumbnail);
			} else if (name.equalsIgnoreCase("image") &&
					parser.getNamespace() == NAMESPACE_ITUNES) {
				String thumbnail = parser.getAttributeValue(null, "href");
				subscriptionValues.put(SubscriptionProvider.COLUMN_THUMBNAIL, thumbnail);
				downloadThumbnail(subscriptionId, thumbnail);
			}
		}
	}
}