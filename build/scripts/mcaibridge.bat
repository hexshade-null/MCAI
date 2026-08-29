@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  mcaibridge startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and MCAIBRIDGE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\mcaibridge-1.0.0.jar;%APP_HOME%\lib\protocol-1.21.11-SNAPSHOT.jar;%APP_HOME%\lib\snakeyaml-2.2.jar;%APP_HOME%\lib\slf4j-simple-2.0.9.jar;%APP_HOME%\lib\javafx-controls-21-mac-aarch64.jar;%APP_HOME%\lib\nbt-3.0.4.Final.jar;%APP_HOME%\lib\MinecraftAuth-5.0.0.jar;%APP_HOME%\lib\adventure-text-serializer-json-legacy-impl-4.25.0.jar;%APP_HOME%\lib\adventure-text-serializer-json-4.25.0.jar;%APP_HOME%\lib\adventure-text-serializer-commons-4.25.0.jar;%APP_HOME%\lib\adventure-api-4.25.0.jar;%APP_HOME%\lib\adventure-nbt-4.25.0.jar;%APP_HOME%\lib\adventure-key-4.25.0.jar;%APP_HOME%\lib\adventure-text-serializer-gson-4.25.0.jar;%APP_HOME%\lib\gson-2.13.2.jar;%APP_HOME%\lib\api-2.0.jar;%APP_HOME%\lib\immutable-2.0.jar;%APP_HOME%\lib\fastutil-object-int-maps-8.5.3.jar;%APP_HOME%\lib\fastutil-int-object-maps-8.5.3.jar;%APP_HOME%\lib\fastutil-int-int-maps-8.5.3.jar;%APP_HOME%\lib\netty-all-4.2.1.Final.jar;%APP_HOME%\lib\checker-qual-3.42.0.jar;%APP_HOME%\lib\slf4j-api-2.0.9.jar;%APP_HOME%\lib\javafx-graphics-21-mac-aarch64.jar;%APP_HOME%\lib\error_prone_annotations-2.41.0.jar;%APP_HOME%\lib\httpclient-1.9.0.jar;%APP_HOME%\lib\gson-1.9.0.jar;%APP_HOME%\lib\fastutil-object-sets-8.5.3.jar;%APP_HOME%\lib\fastutil-int-sets-8.5.3.jar;%APP_HOME%\lib\netty-transport-native-epoll-4.2.1.Final-linux-x86_64.jar;%APP_HOME%\lib\netty-transport-native-epoll-4.2.1.Final-linux-aarch_64.jar;%APP_HOME%\lib\netty-transport-native-epoll-4.2.1.Final-linux-riscv64.jar;%APP_HOME%\lib\netty-transport-native-io_uring-4.2.1.Final-linux-x86_64.jar;%APP_HOME%\lib\netty-transport-native-io_uring-4.2.1.Final-linux-aarch_64.jar;%APP_HOME%\lib\netty-transport-native-io_uring-4.2.1.Final-linux-riscv64.jar;%APP_HOME%\lib\netty-transport-native-kqueue-4.2.1.Final-osx-x86_64.jar;%APP_HOME%\lib\netty-transport-native-kqueue-4.2.1.Final-osx-aarch_64.jar;%APP_HOME%\lib\netty-transport-classes-epoll-4.2.1.Final.jar;%APP_HOME%\lib\netty-transport-classes-io_uring-4.2.1.Final.jar;%APP_HOME%\lib\netty-transport-classes-kqueue-4.2.1.Final.jar;%APP_HOME%\lib\netty-resolver-dns-native-macos-4.2.1.Final-osx-x86_64.jar;%APP_HOME%\lib\netty-resolver-dns-native-macos-4.2.1.Final-osx-aarch_64.jar;%APP_HOME%\lib\netty-resolver-dns-classes-macos-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.1.Final-linux-x86_64.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.1.Final-linux-aarch_64.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.1.Final-osx-x86_64.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.1.Final-osx-aarch_64.jar;%APP_HOME%\lib\netty-codec-native-quic-4.2.1.Final-windows-x86_64.jar;%APP_HOME%\lib\netty-codec-classes-quic-4.2.1.Final.jar;%APP_HOME%\lib\netty-resolver-dns-4.2.1.Final.jar;%APP_HOME%\lib\netty-handler-4.2.1.Final.jar;%APP_HOME%\lib\netty-transport-native-unix-common-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-dns-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-base-4.2.1.Final.jar;%APP_HOME%\lib\netty-transport-4.2.1.Final.jar;%APP_HOME%\lib\netty-buffer-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-haproxy-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-compression-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-http-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-http2-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-memcache-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-mqtt-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-redis-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-smtp-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-socks-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-stomp-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-xml-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-protobuf-4.2.1.Final.jar;%APP_HOME%\lib\netty-codec-marshalling-4.2.1.Final.jar;%APP_HOME%\lib\netty-resolver-4.2.1.Final.jar;%APP_HOME%\lib\netty-common-4.2.1.Final.jar;%APP_HOME%\lib\netty-handler-proxy-4.2.1.Final.jar;%APP_HOME%\lib\netty-handler-ssl-ocsp-4.2.1.Final.jar;%APP_HOME%\lib\netty-transport-rxtx-4.2.1.Final.jar;%APP_HOME%\lib\netty-transport-sctp-4.2.1.Final.jar;%APP_HOME%\lib\netty-transport-udt-4.2.1.Final.jar;%APP_HOME%\lib\javafx-base-21-mac-aarch64.jar;%APP_HOME%\lib\option-1.1.0.jar;%APP_HOME%\lib\examination-string-1.3.0.jar;%APP_HOME%\lib\examination-api-1.3.0.jar;%APP_HOME%\lib\fastutil-object-common-8.5.3.jar;%APP_HOME%\lib\fastutil-int-common-8.5.3.jar;%APP_HOME%\lib\fastutil-common-8.5.3.jar


@rem Execute mcaibridge
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %MCAIBRIDGE_OPTS%  -classpath "%CLASSPATH%" com.mcaibridge.Main %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable MCAIBRIDGE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%MCAIBRIDGE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
