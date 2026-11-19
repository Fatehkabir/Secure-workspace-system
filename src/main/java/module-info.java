module com.examworkspace {
    requires javafx.controls;
    requires java.sql;
    requires java.naming;
    requires java.net.http;
    requires java.desktop;
    requires jdk.httpserver;
    requires org.postgresql.jdbc;
    requires com.h2database;
    requires com.sun.jna;
    requires com.sun.jna.platform;

    exports com.examworkspace;
    exports com.examworkspace.net;
}
