package com.cryptocoinpartners.module.faketicker;


import com.cryptocoinpartners.module.ConfigurationError;
import com.cryptocoinpartners.module.Esper;
import com.cryptocoinpartners.module.ModuleListenerBase;
import com.cryptocoinpartners.schema.*;
import com.cryptocoinpartners.schema.MarketListing;
import com.cryptocoinpartners.util.MathUtil;
import org.apache.commons.configuration.Configuration;
import org.joda.time.Instant;

import java.math.BigDecimal;
import java.util.Random;


/**
 * @author Tim Olson
 */
@SuppressWarnings("FieldCanBeLocal")
public class FakeTicker extends ModuleListenerBase {

    public void initModule(Esper esper, Configuration config) {
        super.initModule(esper, config);
        String marketStr = config.getString("faketicker.market");
        if( marketStr == null )
            throw new ConfigurationError("FakeTicker must be configured with the \"faketicker.market\" property");
        for( String marketName : marketStr.toUpperCase().split(",") ) {
            String upperMarket = marketName.toUpperCase();
            Market market = Market.forSymbol(upperMarket);
            if( market == null )
                throw new ConfigurationError("Could not find Market with symbol \""+ upperMarket +"\"");
            for( MarketListing marketListing : MarketListing.find(market) ) {
                new PoissonTickerThread(marketListing).start();
            }
        }
    }


    public void stop() {
        running = false;
    }


    private BigDecimal nextVolume() {
        return BigDecimal.valueOf(volumeBasis*MathUtil.getPoissonRandom(averageVolume));
    }


    private BigDecimal nextPrice() {
        double delta = random.nextGaussian()*priceMovementStdDev;
        double multiple;
        if( delta < 0 )
            multiple = 1/(1 - delta);
        else
            multiple = 1 + delta;
        currentPrice = currentPrice.multiply(BigDecimal.valueOf(multiple));
        return currentPrice;
    }


    private class PoissonTickerThread extends Thread {
        public void run() {
            running = true;
            while( running ) {
                try {
                    double lambda = 1/averageTimeBetweenTrades;
                    double poissonSleep = -Math.log(1d - random.nextDouble())/lambda;
                    sleep((long) (1000*poissonSleep));
                }
                catch( InterruptedException e ) {
                    break;
                }
                if( !running )
                    break;
                Trade trade = new Trade(marketListing, Instant.now(), null, nextPrice(), nextVolume());
                esper.publish(trade);
            }
        }


        private PoissonTickerThread(MarketListing marketListing ) {
            setDaemon(true);
            this.marketListing = marketListing;
        }


        private final MarketListing marketListing;
    }


    private double averageTimeBetweenTrades = 2;
    private double priceMovementStdDev = 0.0001;
    private double averageVolume = 100.0;
    private double volumeBasis = 1/1000.0;


    private Random random = new Random();
    private BigDecimal currentPrice = BigDecimal.valueOf(100);
    private volatile boolean running;

}
