package org.mp4parser.rtp2dash;

import io.netty.util.concurrent.EventExecutor;
import org.mp4parser.streaming.extensions.TrackIdTrackExtension;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Server {
    ExecutorService es = Executors.newCachedThreadPool();

    private static final Logger LOG = Logger.getLogger(Server.class.getName());

    private int port;

    public Server(int port) {
        this.port = port;
    }

    void extractPlayer(File destDir) throws IOException, ZipException {
        File playerZip = new File(destDir, "player.zip");
        FileOutputStream fos = new FileOutputStream(playerZip);
        IOUtils.copy(getClass().getResourceAsStream("/player.zip"), fos);
        fos.close();

        ZipFile zipFile = new ZipFile(playerZip);
        zipFile.extractAll(destDir.getAbsolutePath());
        playerZip.delete();
    }

    public void run() throws Exception {

        // preparing temp directory and extract player to serve it via HTTP
        final File baseDir = File.createTempFile("live", "server");
        baseDir.delete();
        baseDir.mkdir();
        extractPlayer(baseDir);
        LOG.info("Storing segments and player in " + baseDir);


        CompletionService<Void> ecs
                = new ExecutorCompletionService<Void>(es);
        // The init data is typically parsed from the SDP. As encoder setups are rather static I decided to
        // have a static configuration.
        final RtpH264StreamingTrack h264_0 = new RtpH264StreamingTrack(
                 "Z2QAFUs2QCAb5/ARAAADAAEAAAMAMI8WLZY=,aEquJyw=", 5000, 96);
        h264_0.addTrackExtension(new TrackIdTrackExtension(1)); // trackId becomes AdaptationSetId
        h264_0.addTrackExtension(new RepresentationIdTrackExtension("h264_0"));

        final RtpH264StreamingTrack h264_1 = new RtpH264StreamingTrack(
                 "Z2QAFWs2QCcIebwEQAAAAwBAAAAMI8WLZYA=,aG6uJyw=", 5001, 96);
        h264_1.addTrackExtension(new TrackIdTrackExtension(1));
        h264_1.addTrackExtension(new RepresentationIdTrackExtension("h264_1"));

        final RtpH264StreamingTrack h264_2 = new RtpH264StreamingTrack(
                 "Z2QAHiLNkAwCmwEQAAADABAAAAMDCPFi2WA=,aCEq4nLA", 5002, 96);
        h264_2.addTrackExtension(new TrackIdTrackExtension(1));
        h264_2.addTrackExtension(new RepresentationIdTrackExtension("h264_2"));

        final RtpH264StreamingTrack h264_3 = new RtpH264StreamingTrack(
                 "Z2QAHyrNkASA9sBEAAADAAQAAAMAwjxgxlg=,aClq4nLA", 5003, 96);
        h264_3.addTrackExtension(new TrackIdTrackExtension(1));
        h264_3.addTrackExtension(new RepresentationIdTrackExtension("h264_3"));

        final RtpAacStreamingTrack aac_eng = new RtpAacStreamingTrack(5004, 97, 128, "profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3; config=1190", "MPEG4-GENERIC/48000/2");
        aac_eng.setLanguage("eng");
        aac_eng.addTrackExtension(new TrackIdTrackExtension(2));
        aac_eng.addTrackExtension(new RepresentationIdTrackExtension("aac_eng"));

        final RtpAacStreamingTrack aac_ita = new RtpAacStreamingTrack(5005, 97, 128, "profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3; config=1190", "MPEG4-GENERIC/48000/2");
        aac_ita.setLanguage("ita");
        aac_ita.addTrackExtension(new TrackIdTrackExtension(3));
        aac_ita.addTrackExtension(new RepresentationIdTrackExtension("aac_ita"));

        final List<DashFragmentedMp4Writer> writers = new ArrayList<DashFragmentedMp4Writer>();

        writers.add(new DashFragmentedMp4Writer(aac_ita, baseDir));
        writers.add(new DashFragmentedMp4Writer(aac_eng, baseDir));
        writers.add(new DashFragmentedMp4Writer(h264_0, baseDir));
//        writers.add(new DashFragmentedMp4Writer(h264_1, baseDir));
//        writers.add(new DashFragmentedMp4Writer(h264_2, baseDir));
//        writers.add(new DashFragmentedMp4Writer(h264_3, baseDir));

        List<Future<Void>> rtpReaders = new ArrayList<Future<Void>>();
        rtpReaders.add(ecs.submit(aac_ita));
        rtpReaders.add(ecs.submit(aac_eng));
        rtpReaders.add(ecs.submit(h264_0));
//        rtpReaders.add(ecs.submit(h264_1));
//        rtpReaders.add(ecs.submit(h264_2));
//        rtpReaders.add(ecs.submit(h264_3));

        // The execution will only pass this point if each Rtp[H264|Aac]StreamingTrack has received at least a single packet







        EventLoopGroup bossGroup = new NioEventLoopGroup(); // (1)
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        try {
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("encoder", new HttpResponseEncoder());
                            p.addLast("decoder", new HttpRequestDecoder());
                            p.addLast("aggregator", new HttpObjectAggregator(65536));
                            GregorianCalendar gcNow = GregorianCalendar.from(ZonedDateTime.now());
                            gcNow.setTimeZone(TimeZone.getTimeZone("GMT"));
                            p.addLast("handler", new DashServerHandler(baseDir, gcNow, writers));
                        }
                    });
            Channel ch = b.bind(port).sync().channel();

            boolean complete = false;
            boolean failed = false;
            while (!complete) {
                complete = true;
                Iterator<Future<Void>> futuresIt = rtpReaders.iterator();
                while (futuresIt.hasNext()) {
                    if (futuresIt.next().isDone()) {
                        futuresIt.remove();
                    } else {
                        complete = false;
                    }
                }

                if (!rtpReaders.isEmpty()) {
                    try {
                        ecs.take().get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    } catch (ExecutionException e) {
                        System.out.println("Execution exception " + e.getMessage());
                        e.getCause().printStackTrace();
                        complete = true;
                        failed = true;
                        for (Future<Void> future : rtpReaders) {
                            if (!future.isDone()) {
                                System.out.println("Cancelling " + future);
                                future.cancel(true);
                            }
                        }
                    }
                }
            }
            System.in.read();
            for (DashFragmentedMp4Writer writer : writers) {
                writer.close();
            }
            if (!failed) {
                ch.closeFuture().sync();
            }

        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }


    public static void main(String[] args) throws Exception {
        LogManager.getLogManager().readConfiguration(Server.class.getResourceAsStream("/log.properties"));
        new Server(8080).run();
    }

}
