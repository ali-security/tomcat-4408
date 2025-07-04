/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.valves;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.util.URLEncoder;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;

/**
 * An implementation of the W3c Extended Log File Format. See http://www.w3.org/TR/WD-logfile.html for more information
 * about the format. The following fields are supported:
 * <ul>
 * <li><code>c-dns</code>: Client hostname (or ip address if <code>enableLookups</code> for the connector is false)</li>
 * <li><code>c-ip</code>: Client ip address</li>
 * <li><code>bytes</code>: bytes served</li>
 * <li><code>cs-method</code>: request method</li>
 * <li><code>cs-uri</code>: The full uri requested</li>
 * <li><code>cs-uri-query</code>: The query string</li>
 * <li><code>cs-uri-stem</code>: The uri without query string</li>
 * <li><code>date</code>: The date in yyyy-mm-dd format for GMT</li>
 * <li><code>s-dns</code>: The server dns entry</li>
 * <li><code>s-ip</code>: The server ip address</li>
 * <li><code>cs(xxx)</code>: The value of header xxx from client to server</li>
 * <li><code>sc(xxx)</code>: The value of header xxx from server to client</li>
 * <li><code>sc-status</code>: The status code</li>
 * <li><code>time</code>: Time the request was served</li>
 * <li><code>time-taken</code>: Time (in seconds) taken to serve the request</li>
 * <li><code>x-threadname</code>: Current request thread name (can compare later with stacktraces)</li>
 * <li><code>x-A(xxx)</code>: Pull xxx attribute from the servlet context</li>
 * <li><code>x-C(xxx)</code>: Pull the cookie(s) of the name xxx</li>
 * <li><code>x-O(xxx)</code>: Pull the all response header values xxx</li>
 * <li><code>x-R(xxx)</code>: Pull xxx attribute from the servlet request</li>
 * <li><code>x-S(xxx)</code>: Pull xxx attribute from the session</li>
 * <li><code>x-P(...)</code>: Call request.getParameter(...) and URLencode it. Helpful to capture certain POST
 * parameters.</li>
 * <li>For any of the x-H(...) the following method will be called from the HttpServletRequest object</li>
 * <li><code>x-H(authType)</code>: getAuthType</li>
 * <li><code>x-H(characterEncoding)</code>: getCharacterEncoding</li>
 * <li><code>x-H(connectionId)</code>: getConnectionId</li>
 * <li><code>x-H(contentLength)</code>: getContentLength</li>
 * <li><code>x-H(locale)</code>: getLocale</li>
 * <li><code>x-H(protocol)</code>: getProtocol</li>
 * <li><code>x-H(remoteUser)</code>: getRemoteUser</li>
 * <li><code>x-H(requestedSessionId)</code>: getRequestedSessionId</li>
 * <li><code>x-H(requestedSessionIdFromCookie)</code>: isRequestedSessionIdFromCookie</li>
 * <li><code>x-H(requestedSessionIdValid)</code>: isRequestedSessionIdValid</li>
 * <li><code>x-H(scheme)</code>: getScheme</li>
 * <li><code>x-H(secure)</code>: isSecure</li>
 * </ul>
 * <p>
 * Log rotation can be on or off. This is dictated by the <code>rotatable</code> property.
 * </p>
 * <p>
 * For UNIX users, another field called <code>checkExists</code> is also available. If set to true, the log file's
 * existence will be checked before each logging. This way an external log rotator can move the file somewhere and
 * Tomcat will start with a new file.
 * </p>
 * <p>
 * For JMX junkies, a public method called <code>rotate</code> has been made available to allow you to tell this
 * instance to move the existing log file to somewhere else and start writing a new log file.
 * </p>
 * <p>
 * Conditional logging is also supported. This can be done with the <code>condition</code> property. If the value
 * returned from ServletRequest.getAttribute(condition) yields a non-null value, the logging will be skipped.
 * </p>
 * <p>
 * For extended attributes coming from a getAttribute() call, it is you responsibility to ensure there are no newline or
 * control characters.
 * </p>
 *
 * @author Peter Rossbach
 */
public class ExtendedAccessLogValve extends AccessLogValve {

    private static final Log log = LogFactory.getLog(ExtendedAccessLogValve.class);

    // -------------------------------------------------------- Private Methods

    /**
     * Wrap the incoming value with double quotes (") and escape any double quotes appearing in the value using two
     * double quotes ("").
     *
     * @param value - The value to wrap
     *
     * @return '-' if null. Otherwise, toString() will be called on the object and the value will be wrapped in quotes
     *             and any quotes will be escaped with 2 sets of quotes.
     */
    static String wrap(Object value) {
        String svalue;
        // Does the value contain a " ? If so must encode it
        if (value == null || "-".equals(value)) {
            return "-";
        }

        try {
            svalue = value.toString();
        } catch (Throwable e) {
            ExceptionUtils.handleThrowable(e);
            /* Log error */
            return "-";
        }

        /* Wrap all values in double quotes. */
        StringBuilder buffer = new StringBuilder(svalue.length() + 2);
        buffer.append('\"');
        int i = 0;
        while (i < svalue.length()) {
            int j = svalue.indexOf('\"', i);
            if (j == -1) {
                buffer.append(svalue.substring(i));
                i = svalue.length();
            } else {
                buffer.append(svalue.substring(i, j + 1));
                buffer.append('"');
                i = j + 1;
            }
        }

        buffer.append('\"');
        return buffer.toString();
    }

    @Override
    protected synchronized void open() {
        super.open();
        if (currentLogFile.length() == 0) {
            writer.println("#Fields: " + pattern);
            writer.println("#Version: 2.0");
            writer.println("#Software: " + ServerInfo.getServerInfo());
        }
    }


    // ------------------------------------------------------ Lifecycle Methods


    protected static class DateElement implements AccessLogElement {
        // Milli-seconds in 24 hours
        private static final long INTERVAL = (1000 * 60 * 60 * 24);

        private static final ThreadLocal<ElementTimestampStruct> currentDate =
                ThreadLocal.withInitial(() -> new ElementTimestampStruct("yyyy-MM-dd"));

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            ElementTimestampStruct eds = currentDate.get();
            long millis = eds.currentTimestamp.getTime();
            if (date.getTime() > (millis + INTERVAL - 1) || date.getTime() < millis) {
                eds.currentTimestamp.setTime(date.getTime() - (date.getTime() % INTERVAL));
                eds.currentTimestampString = eds.currentTimestampFormat.format(eds.currentTimestamp);
            }
            buf.append(eds.currentTimestampString);
        }
    }

    protected static class TimeElement implements AccessLogElement {
        // Milli-seconds in a second
        private static final long INTERVAL = 1000;

        private static final ThreadLocal<ElementTimestampStruct> currentTime =
                ThreadLocal.withInitial(() -> new ElementTimestampStruct("HH:mm:ss"));

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            ElementTimestampStruct eds = currentTime.get();
            long millis = eds.currentTimestamp.getTime();
            if (date.getTime() > (millis + INTERVAL - 1) || date.getTime() < millis) {
                eds.currentTimestamp.setTime(date.getTime() - (date.getTime() % INTERVAL));
                eds.currentTimestampString = eds.currentTimestampFormat.format(eds.currentTimestamp);
            }
            buf.append(eds.currentTimestampString);
        }
    }

    protected static class RequestHeaderElement implements AccessLogElement {
        private final String header;

        public RequestHeaderElement(String header) {
            this.header = header;
        }

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            buf.append(wrap(request.getHeader(header)));
        }
    }

    protected static class ResponseHeaderElement implements AccessLogElement {
        private final String header;

        public ResponseHeaderElement(String header) {
            this.header = header;
        }

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            buf.append(wrap(response.getHeader(header)));
        }
    }

    protected static class ServletContextElement implements AccessLogElement {
        private final String attribute;

        public ServletContextElement(String attribute) {
            this.attribute = attribute;
        }

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            buf.append(wrap(request.getContext().getServletContext().getAttribute(attribute)));
        }
    }

    protected static class CookieElement implements AccessLogElement {
        private final String name;

        public CookieElement(String name) {
            this.name = name;
        }

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            StringBuilder value = new StringBuilder();
            boolean first = true;
            Cookie[] c = request.getCookies();
            for (int i = 0; c != null && i < c.length; i++) {
                if (name.equals(c[i].getName())) {
                    if (first) {
                        first = false;
                    } else {
                        value.append(',');
                    }
                    value.append(c[i].getValue());
                }
            }
            if (value.length() == 0) {
                buf.append('-');
            } else {
                buf.append(wrap(value.toString()));
            }
        }
    }

    /**
     * write a specific response header - x-O(xxx)
     */
    protected static class ResponseAllHeaderElement implements AccessLogElement {
        private final String header;

        public ResponseAllHeaderElement(String header) {
            this.header = header;
        }

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            if (null != response) {
                Iterator<String> iter = response.getHeaders(header).iterator();
                if (iter.hasNext()) {
                    StringBuilder buffer = new StringBuilder();
                    boolean first = true;
                    while (iter.hasNext()) {
                        if (first) {
                            first = false;
                        } else {
                            buffer.append(',');
                        }
                        buffer.append(iter.next());
                    }
                    buf.append(wrap(buffer.toString()));
                }
                return;
            }
            buf.append('-');
        }
    }

    protected static class RequestAttributeElement implements AccessLogElement {
        private final String attribute;

        public RequestAttributeElement(String attribute) {
            this.attribute = attribute;
        }

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            buf.append(wrap(request.getAttribute(attribute)));
        }
    }

    protected static class SessionAttributeElement implements AccessLogElement {
        private final String attribute;

        public SessionAttributeElement(String attribute) {
            this.attribute = attribute;
        }

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            HttpSession session = null;
            if (request != null) {
                session = request.getSession(false);
                if (session != null) {
                    buf.append(wrap(session.getAttribute(attribute)));
                }
            }
        }
    }

    protected static class RequestParameterElement implements AccessLogElement {
        private final String parameter;

        public RequestParameterElement(String parameter) {
            this.parameter = parameter;
        }

        /**
         * urlEncode the given string. If null or empty, return null.
         */
        private String urlEncode(String value) {
            if (null == value || value.length() == 0) {
                return null;
            }
            return URLEncoder.QUERY.encode(value, StandardCharsets.UTF_8);
        }

        @Override
        public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
            buf.append(wrap(urlEncode(request.getParameter(parameter))));
        }
    }

    protected static class PatternTokenizer {
        private final StringReader sr;
        private StringBuilder buf = new StringBuilder();
        private boolean ended = false;
        private boolean subToken;
        private boolean parameter;

        public PatternTokenizer(String str) {
            sr = new StringReader(str);
        }

        public boolean hasSubToken() {
            return subToken;
        }

        public boolean hasParameter() {
            return parameter;
        }

        public String getToken() throws IOException {
            if (ended) {
                return null;
            }

            String result = null;
            subToken = false;
            parameter = false;

            int c = sr.read();
            while (c != -1) {
                switch (c) {
                    case ' ':
                        result = buf.toString();
                        buf.setLength(0);
                        buf.append((char) c);
                        return result;
                    case '-':
                        result = buf.toString();
                        buf.setLength(0);
                        subToken = true;
                        return result;
                    case '(':
                        result = buf.toString();
                        buf.setLength(0);
                        parameter = true;
                        return result;
                    case ')':
                        throw new IOException(sm.getString("patternTokenizer.unexpectedParenthesis"));
                    default:
                        buf.append((char) c);
                }
                c = sr.read();
            }
            ended = true;
            if (buf.length() != 0) {
                return buf.toString();
            } else {
                return null;
            }
        }

        public String getParameter() throws IOException {
            String result;
            if (!parameter) {
                return null;
            }
            parameter = false;
            int c = sr.read();
            while (c != -1) {
                if (c == ')') {
                    result = buf.toString();
                    buf = new StringBuilder();
                    return result;
                }
                buf.append((char) c);
                c = sr.read();
            }
            return null;
        }

        public String getWhiteSpaces() throws IOException {
            if (isEnded()) {
                return "";
            }
            StringBuilder whiteSpaces = new StringBuilder();
            if (buf.length() > 0) {
                whiteSpaces.append(buf);
                buf = new StringBuilder();
            }
            int c = sr.read();
            while (Character.isWhitespace((char) c)) {
                whiteSpaces.append((char) c);
                c = sr.read();
            }
            if (c == -1) {
                ended = true;
            } else {
                buf.append((char) c);
            }
            return whiteSpaces.toString();
        }

        public boolean isEnded() {
            return ended;
        }

        public String getRemains() throws IOException {
            StringBuilder remains = new StringBuilder();
            for (int c = sr.read(); c != -1; c = sr.read()) {
                remains.append((char) c);
            }
            return remains.toString();
        }

    }

    @Override
    protected AccessLogElement[] createLogElements() {
        if (log.isTraceEnabled()) {
            log.trace("decodePattern, pattern =" + pattern);
        }
        List<AccessLogElement> list = new ArrayList<>();

        PatternTokenizer tokenizer = new PatternTokenizer(pattern);
        try {

            // Ignore leading whitespace.
            tokenizer.getWhiteSpaces();

            if (tokenizer.isEnded()) {
                log.info(sm.getString("extendedAccessLogValve.emptyPattern"));
                return null;
            }

            String token = tokenizer.getToken();
            while (token != null) {
                if (log.isTraceEnabled()) {
                    log.trace("token = " + token);
                }
                AccessLogElement element = getLogElement(token, tokenizer);
                if (element == null) {
                    break;
                }
                list.add(element);
                String whiteSpaces = tokenizer.getWhiteSpaces();
                if (whiteSpaces.length() > 0) {
                    list.add(new StringElement(whiteSpaces));
                }
                if (tokenizer.isEnded()) {
                    break;
                }
                token = tokenizer.getToken();
            }
            if (log.isTraceEnabled()) {
                log.trace("finished decoding with element size of: " + list.size());
            }
            return list.toArray(new AccessLogElement[0]);
        } catch (IOException e) {
            log.error(sm.getString("extendedAccessLogValve.patternParseError", pattern), e);
            return null;
        }
    }

    protected AccessLogElement getLogElement(String token, PatternTokenizer tokenizer) throws IOException {
        if ("date".equals(token)) {
            return new DateElement();
        } else if ("time".equals(token)) {
            if (tokenizer.hasSubToken()) {
                String nextToken = tokenizer.getToken();
                if ("taken".equals(nextToken)) {
                    nextToken = tokenizer.getToken();

                    if ("ns".equals(nextToken)) {
                        return new ElapsedTimeElement(ElapsedTimeElement.Style.NANOSECONDS);
                    } else if ("us".equals(nextToken)) {
                        return new ElapsedTimeElement(ElapsedTimeElement.Style.MICROSECONDS);
                    } else if ("ms".equals(nextToken)) {
                        return new ElapsedTimeElement(ElapsedTimeElement.Style.MILLISECONDS);
                    } else if ("fracsec".equals(nextToken)) {
                        return new ElapsedTimeElement(ElapsedTimeElement.Style.SECONDS_FRACTIONAL);
                    } else {
                        return new ElapsedTimeElement(ElapsedTimeElement.Style.SECONDS);
                    }
                }
            } else {
                return new TimeElement();
            }
        } else if ("bytes".equals(token)) {
            return new ByteSentElement(true);
        } else if ("cached".equals(token)) {
            /* I don't know how to evaluate this! */
            return new StringElement("-");
        } else if ("c".equals(token)) {
            String nextToken = tokenizer.getToken();
            if ("ip".equals(nextToken)) {
                return new RemoteAddrElement();
            } else if ("dns".equals(nextToken)) {
                return new HostElement();
            }
        } else if ("s".equals(token)) {
            String nextToken = tokenizer.getToken();
            if ("ip".equals(nextToken)) {
                return new LocalAddrElement(getIpv6Canonical());
            } else if ("dns".equals(nextToken)) {
                return new AccessLogElement() {
                    @Override
                    public void addElement(CharArrayWriter buf, Date date, Request request, Response response,
                            long time) {
                        String value;
                        try {
                            value = InetAddress.getLocalHost().getHostName();
                        } catch (Throwable e) {
                            ExceptionUtils.handleThrowable(e);
                            value = "localhost";
                        }
                        buf.append(value);
                    }
                };
            }
        } else if ("cs".equals(token)) {
            return getClientToServerElement(tokenizer);
        } else if ("sc".equals(token)) {
            return getServerToClientElement(tokenizer);
        } else if ("sr".equals(token) || "rs".equals(token)) {
            return getProxyElement(tokenizer);
        } else if ("x".equals(token)) {
            return getXParameterElement(tokenizer);
        }
        log.error(sm.getString("extendedAccessLogValve.decodeError", token));
        return null;
    }

    protected AccessLogElement getClientToServerElement(PatternTokenizer tokenizer) throws IOException {
        if (tokenizer.hasSubToken()) {
            String token = tokenizer.getToken();
            if ("method".equals(token)) {
                return new MethodElement();
            } else if ("uri".equals(token)) {
                if (tokenizer.hasSubToken()) {
                    token = tokenizer.getToken();
                    if ("stem".equals(token)) {
                        return new RequestURIElement();
                    } else if ("query".equals(token)) {
                        return new AccessLogElement() {
                            @Override
                            public void addElement(CharArrayWriter buf, Date date, Request request, Response response,
                                    long time) {
                                String query = request.getQueryString();
                                if (query != null) {
                                    buf.append(query);
                                } else {
                                    buf.append('-');
                                }
                            }
                        };
                    }
                } else {
                    return new AccessLogElement() {
                        @Override
                        public void addElement(CharArrayWriter buf, Date date, Request request, Response response,
                                long time) {
                            String query = request.getQueryString();
                            if (query == null) {
                                buf.append(request.getRequestURI());
                            } else {
                                buf.append(request.getRequestURI());
                                buf.append('?');
                                buf.append(request.getQueryString());
                            }
                        }
                    };
                }
            }
        } else if (tokenizer.hasParameter()) {
            String parameter = tokenizer.getParameter();
            if (parameter == null) {
                log.error(sm.getString("extendedAccessLogValve.noClosing"));
                return null;
            }
            return new RequestHeaderElement(parameter);
        }
        log.error(sm.getString("extendedAccessLogValve.decodeError", tokenizer.getRemains()));
        return null;
    }

    protected AccessLogElement getServerToClientElement(PatternTokenizer tokenizer) throws IOException {
        if (tokenizer.hasSubToken()) {
            String token = tokenizer.getToken();
            if ("status".equals(token)) {
                return new HttpStatusCodeElement();
            } else if ("comment".equals(token)) {
                return new StringElement("?");
            }
        } else if (tokenizer.hasParameter()) {
            String parameter = tokenizer.getParameter();
            if (parameter == null) {
                log.error(sm.getString("extendedAccessLogValve.noClosing"));
                return null;
            }
            return new ResponseHeaderElement(parameter);
        }
        log.error(sm.getString("extendedAccessLogValve.decodeError", tokenizer.getRemains()));
        return null;
    }

    protected AccessLogElement getProxyElement(PatternTokenizer tokenizer) throws IOException {
        String token = null;
        if (tokenizer.hasSubToken()) {
            tokenizer.getToken();
            return new StringElement("-");
        } else if (tokenizer.hasParameter()) {
            tokenizer.getParameter();
            return new StringElement("-");
        }
        log.error(sm.getString("extendedAccessLogValve.decodeError", token));
        return null;
    }

    protected AccessLogElement getXParameterElement(PatternTokenizer tokenizer) throws IOException {
        if (!tokenizer.hasSubToken()) {
            log.error(sm.getString("extendedAccessLogValve.badXParam"));
            return null;
        }
        String token = tokenizer.getToken();
        if ("threadname".equals(token)) {
            return new ThreadNameElement();
        }

        if (!tokenizer.hasParameter()) {
            log.error(sm.getString("extendedAccessLogValve.badXParam"));
            return null;
        }
        String parameter = tokenizer.getParameter();
        if (parameter == null) {
            log.error(sm.getString("extendedAccessLogValve.noClosing"));
            return null;
        }
        if ("A".equals(token)) {
            return new ServletContextElement(parameter);
        } else if ("C".equals(token)) {
            return new CookieElement(parameter);
        } else if ("R".equals(token)) {
            return new RequestAttributeElement(parameter);
        } else if ("S".equals(token)) {
            return new SessionAttributeElement(parameter);
        } else if ("H".equals(token)) {
            return getServletRequestElement(parameter);
        } else if ("P".equals(token)) {
            return new RequestParameterElement(parameter);
        } else if ("O".equals(token)) {
            return new ResponseAllHeaderElement(parameter);
        }
        log.error(sm.getString("extendedAccessLogValve.badXParamValue", token));
        return null;
    }

    protected AccessLogElement getServletRequestElement(String parameter) {
        if ("authType".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap(request.getAuthType()));
                }
            };
        } else if ("remoteUser".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap(request.getRemoteUser()));
                }
            };
        } else if ("requestedSessionId".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap(request.getRequestedSessionId()));
                }
            };
        } else if ("requestedSessionIdFromCookie".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap("" + request.isRequestedSessionIdFromCookie()));
                }
            };
        } else if ("requestedSessionIdValid".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap("" + request.isRequestedSessionIdValid()));
                }
            };
        } else if ("contentLength".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap("" + request.getContentLengthLong()));
                }
            };
        } else if ("connectionId".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap("" + request.getServletConnection().getConnectionId()));
                }
            };
        } else if ("characterEncoding".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap(request.getCharacterEncoding()));
                }
            };
        } else if ("locale".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap(request.getLocale()));
                }
            };
        } else if ("protocol".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap(request.getProtocol()));
                }
            };
        } else if ("scheme".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(request.getScheme());
                }
            };
        } else if ("secure".equals(parameter)) {
            return new AccessLogElement() {
                @Override
                public void addElement(CharArrayWriter buf, Date date, Request request, Response response, long time) {
                    buf.append(wrap("" + request.isSecure()));
                }
            };
        }
        log.error(sm.getString("extendedAccessLogValve.badXParamValue", parameter));
        return null;
    }

    private static class ElementTimestampStruct {
        private final Date currentTimestamp = new Date(0);
        private final SimpleDateFormat currentTimestampFormat;
        private String currentTimestampString;

        ElementTimestampStruct(String format) {
            currentTimestampFormat = new SimpleDateFormat(format, Locale.US);
            currentTimestampFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        }
    }
}
