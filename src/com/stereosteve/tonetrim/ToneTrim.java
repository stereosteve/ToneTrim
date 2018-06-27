package com.stereosteve.tonetrim;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.io.jvm.AudioPlayer;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;
import be.tarsos.dsp.writer.WriterProcessor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.RandomAccessFile;

public class ToneTrim implements PitchDetectionHandler, AudioProcessor {

    AudioFormat format;
    final int bufferSize = 1024;
    final int overlap = 0;
    final double MIN_TONE_LEN = 0.6;
    final double MAX_TONE_LEN = 1.7;
    final double HZ_RANGE = 2.0;
    final double PROB = 0.99;
    AudioDispatcher dispatcher;
    AudioPlayer audioPlayer;
    AudioProcessor fileWriter;
    RandomAccessFile output;
    TarsosDSPAudioFormat audioFormat;
    double toneStartTime = 0;
    String filePath;
    File inputFile;
    int toneCount;
    boolean alreadyFinished;

    public static void main(String[] args) throws Exception {

        if (args.length != 1) {
            System.out.println("filename is required");
            return;
        }

        ToneTrim tt = new ToneTrim(args[0]);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run()
            {
                tt.stop();
            }
        });

        tt.start();
    }

    public ToneTrim(String filePath) {
        this.filePath = filePath;
    }

    public void start() throws Exception {

        // input file
        inputFile = new File(filePath);
        format = AudioSystem.getAudioFileFormat(inputFile).getFormat();
        dispatcher = AudioDispatcherFactory.fromFile(inputFile, bufferSize, overlap);


        // outputs
        setupPlayback();
        setupFile();

        dispatcher.addAudioProcessor(new PitchProcessor(
                PitchProcessor.PitchEstimationAlgorithm.YIN,
                format.getSampleRate(),
                bufferSize,
                this));
        dispatcher.addAudioProcessor(this);
        dispatcher.run();
    }

    public void stop() {
        dispatcher.stop();
    }

    private void setupPlayback() throws Exception {
        audioPlayer = new AudioPlayer(this.format);
    }

    private void setupFile() throws Exception {
        this.output=new RandomAccessFile(filePath.replace(".wav", ".trim.wav"), "rw");
        audioFormat = new TarsosDSPAudioFormat(format.getSampleRate(), format.getSampleSizeInBits(),
                format.getChannels(), true, format.isBigEndian());
        fileWriter = new WriterProcessor(audioFormat, output);
    }

    @Override
    public void handlePitch(PitchDetectionResult pitchDetectionResult, AudioEvent audioEvent) {
        if(pitchDetectionResult.getPitch() != -1){
            double timeStamp = audioEvent.getTimeStamp();
            float pitch = pitchDetectionResult.getPitch();
            float probability = pitchDetectionResult.getProbability();
            double rms = audioEvent.getRMS() * 100;

//            String message = String.format("Pitch detected at %.2fs: %.2fHz ( %.2f probability, RMS: %.5f )\n", timeStamp,pitch,probability,rms);
//            System.out.print(message);

            if (toneStartTime == 0) {
                if (probability > PROB && (Math.abs(pitch - 500) < HZ_RANGE)) {
                    handleToneStart(audioEvent);
                }
            } else if (timeStamp - toneStartTime < MIN_TONE_LEN) {
                // keep tonin'
            } else if (probability < 0.95 || Math.abs(pitch - 220) > HZ_RANGE) {
                handleToneStop(audioEvent);
            }

        }
    }

    void handleToneStart(AudioEvent audioEvent) {
        toneStartTime = audioEvent.getTimeStamp();
    }

    void handleToneStop(AudioEvent audioEvent) {
        double durr = audioEvent.getTimeStamp() - toneStartTime;
        String deets = String.format("(start=%.2fs end=%.2fs durr=%.2fs)", toneStartTime, audioEvent.getTimeStamp(), durr);
        System.out.println(deets);
        toneStartTime = 0;
        toneCount++;
    }

    @Override
    public boolean process(AudioEvent audioEvent) {
        if (this.toneStartTime == 0) {
//            audioPlayer.process(audioEvent);
            fileWriter.process(audioEvent);
        } else {
//            audioPlayer.process(audioEvent);
            if (audioEvent.getTimeStamp() - toneStartTime > MAX_TONE_LEN) {
                this.handleToneStop(audioEvent);
            }
        }
        return true;
    }

    @Override
    public void processingFinished() {
        if (alreadyFinished) return;
        alreadyFinished = true;
        audioPlayer.processingFinished();
        fileWriter.processingFinished();
        System.out.println(String.format("removed %d tones", toneCount));
    }

}
