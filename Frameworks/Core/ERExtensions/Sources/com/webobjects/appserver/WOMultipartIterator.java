package com.webobjects.appserver;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.LinkedList;

import com.webobjects.appserver.WORequest;
import com.webobjects.appserver._private.WOCaseInsensitiveDictionary;
import com.webobjects.appserver._private.WOFileUploadSupport;
import com.webobjects.appserver._private.WOHTTPHeaderValue;
import com.webobjects.appserver._private.WOHTTPHeadersDictionary;
import com.webobjects.appserver._private.WOHttpIO;
import com.webobjects.appserver._private.WOInputStreamData;
import com.webobjects.appserver._private.WONoCopyPushbackInputStream;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSLog;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSNumberFormatter;
import com.webobjects.foundation.NSRange;
import com.webobjects.foundation.NSTimestamp;
import com.webobjects.foundation.NSTimestampFormatter;
import com.webobjects.foundation._NSStringUtilities;

/**
 * Reimplementation to support streams bigger than 2.1GB.
 * 
 * @author jw
 */
public class WOMultipartIterator {
	private LinkedList<WOFormData> _formDataList;
	private LinkedList<WOFormData> _formDataStack;
	int _formDataIndex = 0;

	private int _nextFormDataIndex = 0;

	boolean _closed = false;

	boolean _isFirstFormData = true;

	boolean _prematureTermination = false;
	protected String _boundary;
	byte[] _separator;
	private WOCaseInsensitiveDictionary<String, String> _multipartHeaders;
	protected WORequest _request;
	WONoCopyPushbackInputStream _bis;
	static byte[] dashDash = WOFileUploadSupport._bytesWithAsciiString("--");

	static byte[] CRLF = WOFileUploadSupport._bytesWithAsciiString("\r\n");

	public WOMultipartIterator(WORequest aRequest) {
		_multipartHeaders = new WOCaseInsensitiveDictionary<String, String>();

		_request = aRequest;

		_formDataList = new LinkedList();
		_formDataStack = new LinkedList();

		NSArray<String> aContentHeaderArray = aRequest.headersForKey("content-type");

		int i = 0;
		for (int count = aContentHeaderArray.count(); i < count; i++) {
			WOCaseInsensitiveDictionary tempHeaders = WOFileUploadSupport._parseOneHeader(aContentHeaderArray
					.objectAtIndex(i));
			_multipartHeaders.addEntriesFromDictionary(tempHeaders);
		}
		_boundary = _multipartHeaders.objectForKey("boundary");

		if (aRequest.content() instanceof WOInputStreamData) {
			WOInputStreamData aData = (WOInputStreamData) aRequest.content();
			InputStream is = aData.inputStream();
			if (is != null && is instanceof WONoCopyPushbackInputStream) {
				_bis = (WONoCopyPushbackInputStream) is;
			}
		}

		if (_bis == null) {
			_bis = new WONoCopyPushbackInputStream(aRequest.content().stream(), aRequest._contentLengthHeader());
		}

		_initSeparator();
	}

	public String boundary() {
		return _boundary;
	}

	public NSDictionary<String, String> multipartHeaders() {
		return _multipartHeaders;
	}

	protected void _initSeparator() {
		if (_boundary == null) {
			try {
				byte[] firstBites = new byte[4];

				int firstRead = _bis.read(firstBites);
				if (firstRead == 4) {
					int offset = 0;
					if (firstBites[0] == CRLF[0] && firstBites[1] == CRLF[1]) {
						offset = 2;
					}
					if (firstBites[offset] == dashDash[offset] && firstBites[(offset + 1)] == dashDash[(offset + 1)]) {
						byte[] nextBytes = new byte[1024];
						int nextRead = _bis.read(nextBytes);

						NSData nextData = new NSData(nextBytes, new NSRange(0, nextRead), true);
						NSRange crlfRange = WOFileUploadSupport._rangeOfData(nextData, new NSData(CRLF));

						if (crlfRange.length() > 0) {
							_boundary = _NSStringUtilities.stringForBytes(nextBytes, 0, crlfRange.location(),
									"US-ASCII");

							_bis.unread(nextBytes, 0, nextRead);
							NSLog.err.appendln("Missing multipart boundary parameter; using \"" + _boundary + "\"");
						}
					}
				}
			} catch (Exception e) {
				NSLog.err.appendln("Exception while attempting to find missing boundary string: " + e);
				if (NSLog.debugLoggingAllowedForLevelAndGroups(2, 4L)) {
					NSLog.err.appendln(e);
				}
			}
		}

		if (_boundary != null) {
			_separator = WOFileUploadSupport._bytesWithAsciiString("\r\n--" + _boundary);
		}

		if (_separator == null) {
			_closed = true;
		}
	}

	public boolean didContentTerminatePrematurely() {
		return _prematureTermination;
	}

	public long contentLengthRemaining() {
		return _bis.theoreticallyAvailable();
	}

	public long _estimatedContentLength(int numFileUploads, int numNonFileUploads) {
		long totalRemaining = _bis.originalReadMax();

		int delimiterLength = _separator.length + 4;

		int chaff = numFileUploads * (delimiterLength + 150) + numNonFileUploads * (delimiterLength + 50 + 10)
				+ delimiterLength;

		return totalRemaining - chaff;
	}

	public WOFormData nextFormData() {
		WOFormData bodyPart = null;

		bodyPart = _nextFormDataInList();

		if (bodyPart == null) {
			if (_closed) {
				return null;
			}

			_invalidateFormData(_currentFormData());

			bodyPart = _nextFormData();

			_addFormData(bodyPart);

			_nextFormDataIndex++;
		}

		return bodyPart;
	}

	protected void _invalidateFormData(WOFormData data) {
		if (data != null) {
			data._invalidate();
		}
	}

	protected WOFormData _currentFormData() {
		if (_formDataList.size() > 0) {
			WOFormData current = _formDataList.getLast();
			if (current != null) {
				return current;
			}
		}
		return null;
	}

	protected WOFormData _nextFormData() {
		if (_closed) {
			return null;
		}
		WOFormData bodyPart = null;

		if (_formDataStack.size() > 0) {
			bodyPart = _formDataStack.getFirst();
			_formDataStack.removeFirst();
		} else {
			bodyPart = new WOFormData();

			if (bodyPart._isTheLast) {
				_closed = true;
				bodyPart = null;
			} else {
				try {
					if (bodyPart.isFileUpload()) {
						bodyPart._legacyFormValues(_request._formValues());
					} else {
						bodyPart._addToFormValues(_request._formValues());
					}
				} catch (IOException e) {
					NSLog.err.appendln("Failed to create WOFormData: " + e);
					if (NSLog.debugLoggingAllowedForLevelAndGroups(1, 4L)) {
						NSLog.err.appendln(e);
					}
					_closed = true;
					bodyPart = null;
				}
			}
		}

		return bodyPart;
	}

	private WOFormData _nextFormDataInList() {
		int size = _formDataList.size();
		if ((size > 0) && (size > _nextFormDataIndex)) {
			return _formDataList.get(_nextFormDataIndex++);
		}
		return null;
	}

	protected void _pushFormData(WOFormData newData) {
		if (newData != null) {
			_formDataStack.addFirst(newData);
		}
	}

	protected void _addFormData(WOFormData newData) {
		if (newData != null) {
			_formDataList.addLast(newData);
		}
	}

	public class WOFormData {
		NSDictionary _headers;
		_WOFormDataInputStream _fdstream;
		NSData _data;
		NSDictionary _cdHeaders;
		NSData _cdData;
		int _index = 0;
		boolean _isTheLast = false;
		boolean _isFileUpload = false;
		boolean _streamWasCalled = false;
		boolean _dataWasCalled = false;
		String _formValueString = null;

		protected WOFormData() {
			_headers = null;
			_cdHeaders = null;

			_index = _formDataIndex;
			_formDataIndex++;

			_initHeaders();
			_isFirstFormData = false;
		}

		private void _initHeaders() {
			try {
				WOHttpIO anHttpIo = new WOHttpIO();

				if (_isFirstFormData) {
					int lineLength = 0;
					int sanityCheck = 0;
					while (lineLength == 0 && sanityCheck < 5) {
						lineLength = anHttpIo.readLine(_bis);
						sanityCheck++;
					}
					if (sanityCheck == 5) {
						_isTheLast = true;
						return;
					}
				} else {
					byte[] firstBites = new byte[2];

					int read = _bis.read(firstBites);
					if (read < 2
							|| (firstBites[0] == WOMultipartIterator.dashDash[0] && firstBites[1] == WOMultipartIterator.dashDash[1])) {
						_isTheLast = true;
						return;
					}
					if (firstBites[0] != WOMultipartIterator.CRLF[0] || firstBites[1] != WOMultipartIterator.CRLF[1]) {
						if (firstBites[0] == WOMultipartIterator.CRLF[1]) {
							_bis.unread(firstBites, 1, 1);
						} else {
							_bis.unread(firstBites);
						}
					}

				}

				anHttpIo._readHeaders(_bis, false, false, true);
				_headers = anHttpIo.headers();

				WOHTTPHeadersDictionary _headerDict = (WOHTTPHeadersDictionary) _headers;
				WOCaseInsensitiveDictionary valueDict = null;

				NSArray values = (NSArray) _headerDict._realObjectForKey("content-disposition");

				if (values != null && values.count() > 0) {
					if (values.objectAtIndex(0) instanceof WOHTTPHeaderValue) {
						NSData headerData = ((WOHTTPHeaderValue) values.objectAtIndex(0))._data();

						valueDict = WOFileUploadSupport._parseContentDispositionHeader(_request, null, _headerDict,
								null, headerData);
					}

					values = (NSArray) _headerDict.objectForKey("content-disposition");

					if (valueDict == null) {
						valueDict = WOFileUploadSupport._parseOneHeader((String) values.objectAtIndex(0));
					}

					_cdHeaders = valueDict;
					_headerDict.setObjectForKey(new NSArray(valueDict), "content-disposition");

					_isFileUpload = (_cdHeaders.objectForKey("filename") != null);
				}
			} catch (IOException e) {
				NSLog.err.appendln("Failed to create WOFormData " + e);
				if (NSLog.debugLoggingAllowedForLevelAndGroups(1, 4L)) {
					NSLog.err.appendln(e);
				}
			}
		}

		public boolean isFileUpload() {
			return _isFileUpload;
		}

		public NSDictionary headers() {
			return _headers;
		}

		public NSDictionary contentDispositionHeaders() {
			return _cdHeaders;
		}

		public String name() {
			return (String) _cdHeaders.objectForKey("name");
		}

		public InputStream formDataInputStream() {
			if (_isTheLast) {
				return null;
			}
			if (_dataWasCalled) {
				if (NSLog.debugLoggingAllowedForLevelAndGroups(3, 4L)) {
					NSLog.debug.appendln("<WOFormData>: formDataInputStream() called after accessing formData()");
				}
				return null;
			}
			_streamWasCalled = true;

			if (_fdstream == null) {
				_fdstream = new _WOFormDataInputStream();
			}
			return _fdstream;
		}

		public NSData formData() throws IOException {
			return formData(4096);
		}

		public NSData formData(int bufferSize) throws IOException {
			if (_isTheLast) {
				return null;
			}
			if (_streamWasCalled) {
				if (NSLog.debugLoggingAllowedForLevelAndGroups(3, 4L)) {
					NSLog.debug.appendln("<WOFormData>: formData() called after accessing formDataInputStream()");
				}
				return null;
			}
			_dataWasCalled = true;

			if (_data == null) {
				_fdstream = new _WOFormDataInputStream();
				_data = new NSData(_fdstream, bufferSize);
			}
			return _data;
		}

		public boolean isStreamAvailable() {
			return !_dataWasCalled;
		}

		public String formValue() throws IOException {
			if (_formValueString == null) {
				_formValueString = WOFileUploadSupport._getFormValuesFromData(_request, null, formData(), name());
			}
			return _formValueString;
		}

		protected void _addToFormValues(NSMutableDictionary formValues) throws IOException {
			WOFileUploadSupport._getFormValuesFromData(_request, formValues, formData(), name());
		}

		protected void _legacyFormValues(NSMutableDictionary aFormValues) throws IOException {
			String key = (String) _cdHeaders.objectForKey("name");

			if (key != null) {
				String aFilename = (String) _cdHeaders.objectForKey("filename");
				if (aFilename != null) {
					String newKey = key + "." + "filename";

					NSMutableArray valueArray = (NSMutableArray) aFormValues.objectForKey(newKey);
					if (valueArray != null) {
						valueArray.addObject(aFilename);
					} else {
						aFormValues.setObjectForKey(new NSMutableArray(aFilename), newKey);
					}

				}

				String contentType = null;
				NSArray contentArray = (NSArray) _headers.objectForKey("content-type");
				if ((contentArray != null) && (contentArray.count() > 0)) {
					contentType = (String) contentArray.objectAtIndex(0);
				}
				if (contentType != null) {
					String newKey = key + "." + "mimetype";

					NSMutableArray valueArray = (NSMutableArray) aFormValues.objectForKey(newKey);
					if (valueArray != null) {
						valueArray.addObject(contentType);
					} else {
						aFormValues.setObjectForKey(new NSMutableArray(contentType), newKey);
					}
				}

				NSMutableArray valueArray = (NSMutableArray) aFormValues.objectForKey(key);
				InputStream fdStream = formDataInputStream();
				NSData aBodyData = new WOInputStreamData(fdStream, 0);

				if (valueArray != null) {
					valueArray.addObject(aBodyData);
				} else {
					aFormValues.setObjectForKey(new NSMutableArray(aBodyData), key);
				}
			}
		}

		public Number numericFormValue(NSNumberFormatter numericFormatter) throws IOException {
			String numberString = formValue();
			Number number = null;

			if ((numberString != null) && (numericFormatter != null)) {
				try {
					number = (Number) numericFormatter.parseObject(numberString);
				} catch (ParseException e) {
					if (NSLog.debugLoggingAllowedForLevelAndGroups(2, 128L)) {
						NSLog.err.appendln(e);
					}
				}
			}
			return number;
		}

		public NSTimestamp dateFormValue(NSTimestampFormatter dateFormatter) throws IOException {
			String aDateString = formValue();
			NSTimestamp aDate = null;

			if ((aDateString != null) && (dateFormatter != null)) {
				try {
					aDate = (NSTimestamp) dateFormatter.parseObject(aDateString);
				} catch (ParseException e) {
					if (NSLog.debugLoggingAllowedForLevelAndGroups(2, 128L)) {
						NSLog.err.appendln(e);
					}
				}
			}
			return aDate;
		}

		void _invalidate() {
			if ((_isFileUpload) || (_streamWasCalled)) {
				if (_fdstream == null) {
					_fdstream = new _WOFormDataInputStream();
				}
				try {
					_fdstream.close();
				} catch (IOException ioe) {
					NSLog.err.appendln("WOFormData failed to skip past data: " + ioe);
					if (NSLog.debugLoggingAllowedForLevelAndGroups(1, 4L)) {
						NSLog.err.appendln(ioe);
					}
				}
			} else {
				try {
					formData();
				} catch (IOException ioe) {
					NSLog.err.appendln("WOFormData failed to read data: " + ioe);
					if (NSLog.debugLoggingAllowedForLevelAndGroups(1, 4L)) {
						NSLog.err.appendln(ioe);
					}
				}
			}
		}

		public boolean isStreamValid() {
			if (_fdstream != null) {
				return !_fdstream.isClosed();
			}
			return false;
		}

		@Override
		public String toString() {
			if (_isTheLast) {
				return "<WOFormData>: This WOFormData represents the end of the multipart form data";
			}
			return "WOFormData " + _index + " isStreamValid " + isStreamValid() + " headers: " + _headers;
		}

		protected class _WOFormDataInputStream extends InputStream {
			private boolean _streamClosed;
			private byte[] _oneByteArray;
			private byte[] _drainBuffer;
			private int _drainBufferLength = 4096;

			protected _WOFormDataInputStream() {
				_streamClosed = false;
				_oneByteArray = new byte[1];
			}

			@Override
			public int available() {
				if (_streamClosed) {
					return -1;
				}
				return 0;
			}

			@Override
			public void close() throws IOException {
				while (!_streamClosed) {
					skip(9223372036854775807L);
				}
			}

			@Override
			public int read() throws IOException {
				int read = read(_oneByteArray);
				if (read == -1) {
					return read;
				}
				return _oneByteArray[0];
			}

			@Override
			public int read(byte[] b) throws IOException {
				return read(b, 0, b.length);
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (b == null) {
					throw new IllegalArgumentException("<" + getClass().getName() + ">: buffer passed is null!");
				}
				if (len == 0) {
					return 0;
				}
				if (off < 0 || len < 0 || off + len > b.length) {
					throw new IndexOutOfBoundsException("<" + getClass().getName() + ">: attempted to read " + len
							+ " bytes into buffer of length " + b.length + " at offset " + off);
				}
				if (_streamClosed) {
					return -1;
				}
				int bytesRead = 0;
				try {
					int charsMatched = 0;
					int separatorLength = _separator.length;

					bytesRead = _bis.read(b, off, len);

					for (int i = 0; i < bytesRead; i++) {
						charsMatched = 0;
						while ((i + charsMatched < bytesRead) && (charsMatched < separatorLength)
								&& (b[(off + i + charsMatched)] == _separator[charsMatched])) {
							charsMatched++;
						}

						if (charsMatched == separatorLength) {
							_streamClosed = true;

							int pbsize = bytesRead - i - separatorLength;

							if (pbsize > 0) {
								byte[] tempB = new byte[pbsize];
								System.arraycopy(b, i + off + separatorLength, tempB, 0, pbsize);
								_bis.unread(tempB);
							}

							return i;
						}
						if (i + charsMatched != bytesRead) {
							continue;
						}
						byte[] tempB = new byte[separatorLength - charsMatched];

						int readB = 0;
						do {
							int justRead = _bis.read(tempB, readB, tempB.length - readB);
							if (justRead == -1) {
								break;
							}
							readB += justRead;
						} while (readB < tempB.length);

						int bIndex = 0;
						boolean bv = true;
						do {
							if (bIndex >= readB || charsMatched >= separatorLength) {
								bv = false;
								break;
							}
						} while (tempB[bIndex++] == _separator[charsMatched++]);

						if (bv) {
							_bis.unread(tempB, 0, readB);
							return bytesRead;
						}

						_streamClosed = true;

						if (charsMatched == separatorLength) {
							return i;
						}
						if (bIndex == readB) {
							return bytesRead;
						}
					}
				} catch (IOException e) {
					if (_bis.wasPrematurelyTerminated()) {
						_closed = true;
						_prematureTermination = true;
					}
					throw e;
				}
				return bytesRead;
			}

			@Override
			public long skip(long n) throws IOException {
				if (_drainBuffer == null) {
					_drainBuffer = new byte[_drainBufferLength];
				}

				long left = n;
				int count = 0;
				try {
					while (left > 0L) {
						count = read(_drainBuffer, 0, (int) (left > _drainBufferLength ? _drainBufferLength : left));
						if (count == -1) {
							_streamClosed = true;
							return n - left;
						}
						left -= count;
					}
				} catch (IOException e) {
					if (_bis.wasPrematurelyTerminated()) {
						_closed = true;
						_prematureTermination = true;
					}
					throw e;
				}
				return n;
			}

			public boolean isClosed() {
				return _streamClosed;
			}
		}
	}
}