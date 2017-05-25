package org.cryptocoinpartners.module;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.persistence.Transient;

import org.apache.commons.configuration.Configuration;
import org.cryptocoinpartners.enumeration.ExecutionInstruction;
import org.cryptocoinpartners.enumeration.FillType;
import org.cryptocoinpartners.enumeration.OrderState;
import org.cryptocoinpartners.enumeration.PositionEffect;
import org.cryptocoinpartners.enumeration.PositionType;
import org.cryptocoinpartners.enumeration.TransactionType;
import org.cryptocoinpartners.esper.annotation.When;
import org.cryptocoinpartners.schema.Amount;
import org.cryptocoinpartners.schema.Asset;
import org.cryptocoinpartners.schema.Bar;
import org.cryptocoinpartners.schema.Book;
import org.cryptocoinpartners.schema.DecimalAmount;
import org.cryptocoinpartners.schema.DiscreteAmount;
import org.cryptocoinpartners.schema.Fill;
import org.cryptocoinpartners.schema.GeneralOrder;
import org.cryptocoinpartners.schema.Listing;
import org.cryptocoinpartners.schema.Market;
import org.cryptocoinpartners.schema.Offer;
import org.cryptocoinpartners.schema.Order;
import org.cryptocoinpartners.schema.OrderBuilder;
import org.cryptocoinpartners.schema.OrderBuilder.CommonOrderBuilder;
import org.cryptocoinpartners.schema.OrderUpdate;
import org.cryptocoinpartners.schema.Portfolio;
import org.cryptocoinpartners.schema.Position;
import org.cryptocoinpartners.schema.PositionUpdate;
import org.cryptocoinpartners.schema.SpecificOrder;
import org.cryptocoinpartners.schema.Trade;
import org.cryptocoinpartners.schema.Tradeable;
import org.cryptocoinpartners.schema.Transaction;
import org.cryptocoinpartners.util.ConfigUtil;
import org.cryptocoinpartners.util.FeesUtil;
import org.cryptocoinpartners.util.Remainder;
import org.knowm.xchange.currency.Currency;

import com.espertech.esper.client.deploy.DeploymentException;
import com.espertech.esper.client.deploy.ParseException;

/**
 * This simple Strategy first waits for Book data to arrive about the target Market, then it places a buy order
 * at demostrategy.spread below the current bestAsk.  Once it enters the trade, it places a sell order at
 * demostrategy.spread above the current bestBid.
 * This strategy ignores the available Positions in the Portfolio and always trades the amount set by demostrategy.volume on
 * the Market specified by demostrategy.market
 *
 * @author Tim Olson
 */
@SuppressWarnings("UnusedDeclaration")
public abstract class BaseMomentumStrategy extends SimpleStatefulStrategy {
    static double percentEquityRisked;
    static double positionInertia;
    //1 day 2 day with 1 atr and twice stop is 61% wins, 1.6 win loss, 50% exencacys
    static double atrStop;
    static double atrTarget;
    static double atrTrigger;
    //* atrStop;
    static double slippage;;
    static double slippagePercentage;
    static long timeToLive;
    static long exitTimeToLive;
    static long triggerBuffer;
    static boolean fixedBalance = true;
    static double maxLossTarget;
    static double volatilityInterval;
    static boolean forceStops = false;
    static boolean riskManageNotionalBalance = true;
    static boolean rawSignals = ConfigUtil.combined().getBoolean("strategy.rawticks", false);
    static Amount balanceScalingAmount = DecimalAmount.ZERO;
    static boolean adjustStops = false;
    static double volatilityTarget;
    //5/3 hour bars with 0.01 risk, 2 atr no traget, notional of 100,000
    //5/10 day with 0.01 risk, 2 atr, no target, notional of 100,000

    // as we reduce teh time period, teh atr is smaller, so the unit size is bigger, so we neeed to bet less.
    // static double percentEquityRisked = 0.01;
    //1 day 2 day with 1 atr and twice stop is 61% wins, 1.6 win loss, 50% exencacys
    //static double atrStop = 1;
    //static double atrTarget = 2000000;
    //* atrStop;
    // static long slippage = 0;
    // static double maxLossTarget = atrStop * percentEquityRisked;
    static double lossScalingFactor = 2;
    static DecimalAmount baseFixedMaxLoss = DecimalAmount.of("5000");
    static Amount previousBal = null;
    static Amount previousRestBal = null;
    static Amount startingOrignalBal = null;
    //double maxLossTarget = 0.25;
    //  static Amount maxPositionUnits = DecimalAmount.of("100");
    //static Amount basePrice = DecimalAmount.of("250");
    // static Amount maxUnitSize = DecimalAmount.of("1000");

    static Amount marginBuffer = DecimalAmount.of("0.2");
    static CountDownLatch startSignal = new CountDownLatch(1);
    static CountDownLatch endSignal = new CountDownLatch(1);
    private static ExecutorService service = Executors.newFixedThreadPool(1);
    protected static ExecutorService cancellationService = Executors.newFixedThreadPool(1);
    static Future future;
    //  static Amount notionalBaseBalance;
    static Amount notionalBalance;
    static Amount startingBaseNotionalBalance;
    static Amount startingNotionalBalance;
    //= new DiscreteAmount(Long.parseLong("10000000"), Asset.forSymbol("USD").getBasis());

    // List<String> notionalBalance = Arrays.asList("OKCOIN:USD", "10000000");
    long entryPrice;
    private DiscreteAmount lastLongExitLimit;
    private DiscreteAmount lastShortExitLimit;
    private double minSpread;
    private static Object lock = new Object();

    protected static final ConcurrentHashMap<Market, PositionUpdate> positionMap = new ConcurrentHashMap<Market, PositionUpdate>();

    @Inject
    public BaseMomentumStrategy(Context context, Configuration config) {

        // String marketSymbol = config.getString("demostrategy.market","BITFINEX:BTC.USD");
        // String marketSymbol = ("OKCOIN_THISWEEK:BTC.USD.THISWEEK");

        //notionalBalanceUSD = new DiscreteAmount(Long.parseLong("10000000"), market.getQuote().getBasis());
        //originalNotionalBalanceUSD = new DiscreteAmount(Long.parseLong("20000000"), market.getQuote().getBasis());

        // market = Market.forSymbol(marketSymbol);
        //if (market == null)
        //throw new Error("Could not find Market for symbol " + marketSymbol);

        // = DiscreteAmount.roundedCountForBasis(volumeBD, market.getVolumeBasis());
    }

    @When("@Priority(9) select * from OrderUpdate where state.cancelled=true")
    void handleCancelledOrder(OrderUpdate update) {
        //   if (getShortMov() > 0)
        revertPositionMap(update.getOrder().getMarket());
    }

    public static class LongHighDataSubscriber {

        // private final Logger logger = Logger.getLogger(DataSubscriber.class.getName());

        public void update(double high) {
            //handleLongHighIndicator(high);
            log.info("Long high: " + high);
        }
    }

    public static class LongLowDataSubscriber {

        public void update(double low) {
            //	handleLongLowIndicator(low);
            log.info("Long Low: " + low);
        }
    }

    public static class ShortHighDataSubscriber {

        public void update(double high) {
            //handleShortHighIndicator(high);
            log.info("Long Reentry: " + high);
        }

        public void update(Fill fill) {
            //handleShortHighIndicator(high);
            log.info("Short High: " + fill.toString());
        }

        public void update(double high, long vol) {
            //handleShortHighIndicator(high);
            log.info("Short High: " + high + " " + vol);
        }

        public void update(OrderUpdate amount) {
            //handleShortHighIndicator(high);
            log.info("Short High: " + amount.toString());
        }

        public void update(Amount stopprice) {
            //handleShortHighIndicator(high);
            log.info("Short High: " + stopprice.toString());
        }
    }

    public static class ShortLowDataSubscriber {

        public void update(double low) {
            //	handleShortLowIndicator(low);
            log.info("Short Low: " + low);
        }
    }

    public static class DataSubscriber {

        public void update(double avgValue, double countValue, double minValue, double maxValue) {
            log.info(avgValue + "," + countValue + "," + minValue + "," + maxValue);

        }

        public void update(Bar bar) {
            log.info("LastBar:" + bar.toString());
        }

        public void update(long time, double high) {

            log.info("time:" + time + " high:" + high);
        }

        public void update(double time, long high) {

            log.info("time:" + time + " high:" + high);
        }

        public void update(Trade trade) {

            log.trace("Trade:" + trade.toString());
        }

        public void update(double maxValue, double minValue, double atr) {
            log.info("High:" + maxValue + " Low:" + minValue + " ATR:" + atr);
        }

        public void update(double maxValue, double minValue, long timestamp) {
            log.info("Trade Price:" + maxValue + " Prevoius Max:" + minValue + " Timestamp:" + timestamp);
        }

        public void update(Bar firstBar, Bar lastBar) {
            log.info("LastBar:" + lastBar.toString());

        }

        public void update(double atr) {
            log.info("REBALACE" + atr);
        }

        @SuppressWarnings("rawtypes")
        public void update(Map insertStream, Map removeStream) {

            log.info("high:" + insertStream.get("high") + " Low:" + insertStream.get("low"));
        }

    }

    //@When("select low from ShortLowIndicator")
    // @When("@Priority(3) on LastTradeWindow as trigger select trigger.priceAsDouble from ShortLowIndicator where trigger.priceCountAsDouble<ShortLowIndicator.low")

    //@When("@Priority(3) on ShortLowIndicator as trigger select trigger.low from ShortLowIndicator")
    //@When("@Priority(3) on LastTradeWindow as trigger select trigger.priceAsDouble from ShortLowIndicator where trigger.priceCountAsDouble<ShortLowIndicator.low")
    // @When("@Priority(3) on LastTradeWindow as trigger select trigger.priceAsDouble  from ShortLowIndicator where trigger.priceCountAsDouble<min(ShortLowIndicator.ohlclow, ShortLowIndicator.tradeslow)")
    //on LastTradeWindow as trigger select ShortLowIndicator
    /// orig  
    //  
    // 
    /// orig   
    // @When("@Priority(4) on LastTradeWindow as trigger select trigger.priceAsDouble from ShortLowIndicator where trigger.priceCountAsDouble<ShortLowIndicator.low")
    // counter  @When("on LastTradeWindow as trigger select trigger.priceAsDouble from ShortHighIndicator where trigger.priceCountAsDouble>ShortHighIndicator.high")

    // @When("@Priority(4) on LastBookWindow as trigger select trigger.bidPriceCountAsDouble from ShortLowTradeIndicator where trigger.bidPriceCountAsDouble<ShortLowTradeIndicator.low")
    //  @When("@Priority(4) on LastTradeWindow as trigger select trigger.bidPriceCountAsDouble from ShortLowBidIndicator where trigger.bidPriceCountAsDouble<ShortLowBidIndicator.low")
    //@When("@Priority(4) on LastBookWindow as trigger select trigger.bidPriceCountAsDouble from ShortLowBidIndicator where trigger.bidPriceCountAsDouble<ShortLowBidIndicator.low")
    // counter      @When("@Priority(4) on LastTradeWindow as trigger select trigger.askPriceCountAsDouble from ShortHighAskIndicator where trigger.askPriceCountAsDouble>ShortHighAskIndicator.high")
    // this one @When("@Priority(4) on LastTradeWindow as trigger select trigger.priceCountAsDouble from ShortLowTradeIndicator where trigger.priceCountAsDouble<ShortLowTradeIndicator.low")
    // counter  @When("on LastTradeWindow as trigger select trigger.priceAsDouble from ShortHighIndicator where trigger.priceCountAsDouble>ShortHighIndicator.high")
    //runs 
    // 
    //@When("@Priority(4) on ShortLowRunIndicator as trigger select trigger.low from ShortLowRunIndicator")
    // bar counter @When("@Priority(4) on ShortLowRunBarIndicator as trigger select trigger.low from ShortLowRunBarIndicator")
    //turtles
    // trned ok @When("@Priority(4) on LastTradeWindow as trigger select trigger.priceCountAsDouble from ShortLowTradeIndicator where trigger.priceCountAsDouble<ShortLowTradeIndicator.low")
    // counter  @When("@Priority(4) on ShortHighRunBarIndicator as trigger select trigger.high from ShortHighRunBarIndicator")
    void handleShortLowIndicator(double interval, double d, ExecutionInstruction execInst, Market market) {
        if (!orderService.getTradingEnabled())
            return;

        // selling at the bid

        //        if (future.isDone())
        //            future.get();
        //        } catch (CancellationException ce) {
        //            t = ce;
        //        } catch (ExecutionException ee) {
        //            t = ee.getCause();
        //        } catch (InterruptedException ie) {
        //            Thread.currentThread().interrupt(); // ignore/reset
        //        }

        //		counter++;
        //		log.info(String.valueOf(counter));
        //		//		//new high price so we will enter a long trades
        //
        //  synchronized (lock) {
        // service.ex
        // first time through the last price will be 0
        //   if (lastLongExitPrice != 0 && d >= lastLongExitPrice)
        //     return;
        lastLongExitPrice = d;

        log.info("Long Exit Triggered at " + context.getTime() + " book: " + quotes.getLastBook(market) + " positions " + getPositions(market));
        //  synchronized (lock) {

        //  if (orderService.getPendingOrders(portfolio) == null || orderService.getPendingOrders(portfolio).isEmpty())
        //    revertPositionMap(market);
        try {
            buildExitLongOrders(interval, execInst, quotes.getLastBook(market), market);
        } catch (Exception ex) {
            revertPositionMap(market);
            log.error("Threw a Execption, full stack trace follows:", ex);

            //  ex.getCause().printStackTrace();
        }
        /*  if (future == null || future.isDone()) {
              //  if (positionMap.get(market).getType() == PositionType.LONG) {
              //    cancellationService.submit(new handleCancelAllSpecificOrders(portfolio, market));
              // cancellationService.submit(new handleCancelAllLongClosingSpecificOrders(portfolio, market, ExecutionInstruction.TAKER));

              //updatePositionMap(market, PositionType.EXITING);
              //if (!orderService.getPendingOpenOrders(portfolio).isEmpty())
              //    return;
              future = service.submit(new buildExitLongOrders(execInst, quotes.getLastBook(market)));
              try {
                  //   if (future.isDone())
                  future.get();
              } catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  revertPositionMap(market);
                  log.error("Threw a Execption, full stack trace follows:", e);

                  e.printStackTrace();
                  Thread.currentThread().interrupt();

              } catch (ExecutionException ex) {
                  log.error("Threw a Execption, full stack trace follows:", ex);

                  revertPositionMap(market);
                  ex.getCause().printStackTrace();

              }*/

        //  startSignal.countDown();
        // endSignal.await();

        // } else if (positionMap.get(market) == null || positionMap.get(market).getType() == PositionType.EXITING) {
        //   cancellationService.submit(new handleCancelAllSpecificOrders(portfolio, market));
        // return;

        // } else {
        //   PositionUpdate psotion = positionMap.get(market);
        //     log.info("Long Exit Prevented as no long postion at " + context.getTime());
        // return;
        // }
        //   } else {
        //     log.info("Long Exit as thread not finished running " + context.getTime());

        //   return;
        //    }

        //}
        //        ArrayList<CommonOrderBuilder> orderList = buildExitLongOrders();
        //        if (orderList == null)
        //            return;
        //        Iterator<OrderBuilder.CommonOrderBuilder> itob = orderList.iterator();
        //        while (itob.hasNext()) {
        //            OrderBuilder.CommonOrderBuilder orderBuilder = itob.next();
        //            placeOrder(orderBuilder);
        //        }
        //  }
        // }

        //	OrderBuilder.CommonOrderBuilder orderBuilder = buildExitLongOrders();

        //	placeOrder(orderBuilder);

        //		log.info("Portfolio: " + portfolio + " Total Value (" + portfolio.getBaseAsset() + "):"
        //				+ portfolioService.getCashBalance().plus(portfolioService.getMarketValue()) + " (Cash Balance:" + portfolioService.getCashBalance()
        //				+ " Open Trade Equity:" + portfolioService.getMarketValue() + ")");
    }

    //@When("select high from ShortHighIndicator")

    // @When("@Priority(3) on ShortHighIndicator as trigger select trigger.high from ShortHighIndicator")
    //@When("@Priority(3) on LastTradeWindow as trigger select trigger.priceAsDouble from ShortHighIndicator where trigger.priceCountAsDouble>ShortHighIndicator.high")
    // @When("@Priority(3) on LastTradeWindow as trigger select trigger.priceAsDouble  from ShortHighIndicator where trigger.priceCountAsDouble>max(ShortHighIndicator.ohlchigh, ShortHighIndicator.tradeshigh)")
    // orig  
    // 
    //   @When("@Priority(4) on LastTradeWindow as trigger select trigger.askPriceCountAsDouble from ShortHighAskIndicator where trigger.askPriceCountAsDouble>ShortHighAskIndicator.high")
    // @When("@Priority(4)  on LastBookWindow as trigger select trigger.askPriceCountAsDouble  from ShortHighTradeIndicator where trigger.askPriceCountAsDouble>ShortHighTradeIndicator.high")
    // @When("@Priority(4) on LastBookWindow as trigger select trigger.askPriceCountAsDouble from ShortHighAskIndicator where trigger.askPriceCountAsDouble>ShortHighAskIndicator.high")
    //counter    @When("@Priority(4) on LastTradeWindow as trigger select trigger.bidPriceCountAsDouble from ShortLowBidIndicator where trigger.bidPriceCountAsDouble<ShortLowBidIndicator.low")
    // this one @When("@Priority(4) on LastTradeWindow as trigger select trigger.priceCountAsDouble from ShortHighTradeIndicator where trigger.priceCountAsDouble>ShortHighTradeIndicator.high")
    //counter   @When("on LastTradeWindow as trigger select trigger.priceAsDouble from ShortLowIndicator where trigger.priceCountAsDouble<ShortLowIndicator.low")
    // runs  
    // @When("@Priority(4) on ShortHighRunIndicator as trigger select trigger.high from ShortHighRunIndicator")
    // bar counter @When("@Priority(4) on ShortHighRunBarIndicator as trigger select trigger.high from ShortHighRunBarIndicator")
    // trned ok @When("@Priority(4) on LastTradeWindow as trigger select trigger.priceCountAsDouble from ShortHighTradeIndicator where trigger.priceCountAsDouble>ShortHighTradeIndicator.high")
    // counter @When("@Priority(4) on ShortLowRunBarIndicator as trigger select trigger.low from ShortLowRunBarIndicator")

    void handleShortHighIndicator(double interval, double d, ExecutionInstruction execInst, Market market) {
        if (!orderService.getTradingEnabled())
            return;

        // so I am buying at the ask
        //new high price so we will enter a long trades
        //		counter++;
        //		log.info(String.valueOf(counter));
        //  
        //    synchronized (lock) {
        //   if (lastShortExitPrice != 0 && d <= lastShortExitPrice)
        //      return;
        lastShortExitPrice = d;
        log.info("Short Exit Triggered for interval " + interval + " at " + context.getTime() + " book: " + quotes.getLastBook(market) + " positions "
                + getPositions(market));

        // synchronized (lock) {
        //    if (orderService.getPendingOrders(portfolio) == null || orderService.getPendingOrders(portfolio).isEmpty())
        //      revertPositionMap(market);
        // if (future == null || future.isDone()) {
        //  if (positionMap.get(market).getType() == PositionType.SHORT) {
        //  cancellationService.submit(new handleCancelAllSpecificOrders(portfolio, market));
        //cancellationService.submit(new handleCancelAllShortClosingSpecificOrders(portfolio, market, ExecutionInstruction.TAKER));

        //cancellationService.submit(new handleCancelAllShortStopOrders(portfolio, market));

        //updatePositionMap(market, PositionType.EXITING);
        //if (!orderService.getPendingOpenOrders(portfolio).isEmpty())
        try {
            buildExitShortOrders(interval, execInst, quotes.getLastBook(market), market);
        } catch (Exception ex) {
            revertPositionMap(market);
            log.error("Threw a Execption, full stack trace follows:", ex);

            //  ex.getCause().printStackTrace();
        }
        //  return;
        /*  future = service.submit(new buildExitShortOrders(execInst, quotes.getLastBook(market)));
          try {
              // if (future.isDone())
              future.get();
          } catch (InterruptedException e) {

              // TODO Auto-generated catch block
              revertPositionMap(market);
              log.error("Threw a Execption, full stack trace follows:", e);

              e.printStackTrace();
              Thread.currentThread().interrupt();

          } catch (ExecutionException ex) {
              revertPositionMap(market);
              log.error("Threw a Execption, full stack trace follows:", ex);

              ex.getCause().printStackTrace();

          }*/
        //  } //else {
        //log.info("Short Exit not complete as position map is not short " + context.getTime());
        // }

        //  startSignal.countDown();
        // endSignal.await();

        //  } //else if (positionMap.get(market) == null || positionMap.get(market).getType() == PositionType.EXITING) {
        //cancellationService.submit(new handleCancelAllSpecificOrders(portfolio, market));
        //return;

        // }

        //   else {
        //  log.info("Short Exit Prevented as already no short postion at " + context.getTime());
        //     return;
        // }
        // } else {
        //   log.info("Short Exit as thread not finished running " + context.getTime());

        // return;
        //  }

        //        ArrayList<CommonOrderBuilder> orderList = buildExitShortOrders();
        //        if (orderList == null)
        //            return;
        //        Iterator<OrderBuilder.CommonOrderBuilder> itob = orderList.iterator();
        //        while (itob.hasNext()) {
        //            OrderBuilder.CommonOrderBuilder orderBuilder = itob.next();
        //            placeOrder(orderBuilder);
        //
        //        }
        //    }
        //   }

        //	log.info("Portfolio: " + portfolio + " Total Value (" + portfolio.getBaseAsset() + "):"
        //		+ portfolioService.getCashBalance().plus(portfolioService.getMarketValue()) + " (Cash Balance:" + portfolioService.getCashBalance()
        //	+ " Open Trade Equity:" + portfolioService.getMarketValue() + ")");
    }

    //@When("select low from LowIndicator")
    //@When("select high from LongHighIndicator")

    //    @When("@Priority(2) on LastTradeWindow as trigger select trigger.priceAsDouble from LastStopPriceWindow where trigger.priceCountAsDouble>LastStopPriceWindow.priceAsDouble and LastStopPriceWindow.volumeCount<0")
    synchronized void handleLongRentryIndicator(double d, Market market, Market pairMarket) {
        if (!orderService.getTradingEnabled())
            return;
        //  handleLongHighIndicator(1, 1, d, ExecutionInstruction.TAKER, market, pairMarket);
    }

    //  @When("@Priority(2) on LastTradeWindow as trigger select trigger.priceAsDouble from LastStopPriceWindow where trigger.priceCountAsDouble<LastStopPriceWindow.priceAsDouble and LastStopPriceWindow.volumeCount>0")
    synchronized void handleShortRentryIndicator(double d, Market market, Market pairMarket) {
        if (!orderService.getTradingEnabled())
            return;
        handleLongLowIndicator(1, 1, d, ExecutionInstruction.TAKER, market, pairMarket);
    }

    // @When("@Priority(2) on LastTradeWindow as trigger select trigger.priceAsDouble from LongHighIndicator where trigger.priceCountAsDouble>LongHighIndicator.high")
    // @When("@Priority(2) on LastTradeWindow as trigger select trigger.priceAsDouble from LongHighIndicator where trigger.priceCountAsDouble>=max(LongHighIndicator.ohlchigh, LongHighIndicator.tradeshigh)")
    // orig       
    // 
    // @When("@Priority(3) on LastTradeWindow as trigger select trigger.askPriceCountAsDouble from LongHighAskIndicator where trigger.askPriceCountAsDouble>LongHighAskIndicator.high")
    // @When("@Priority(3) on LastBookWindow as trigger select trigger.askPriceCountAsDouble from LongHighTradeIndicator where trigger.askPriceCountAsDouble>LongHighTradeIndicator.high")
    //@When("@Priority(3) on LastTradeWindow as trigger select trigger.priceCountAsDouble from LongHighTradeIndicator where trigger.priceCountAsDouble>LongHighTradeIndicator.high")
    //counter @When("on LastTradeWindow as trigger select trigger.priceAsDouble from LongLowIndicator where trigger.priceCountAsDouble<LongLowIndicator.low")
    //@When("@Priority(3) on LastBookWindow as trigger select trigger.bidPriceCountAsDouble from LongHighBidIndicator where trigger.bidPriceCountAsDouble>LongHighBidIndicator.high")
    //counter  @When("@Priority(3) on LastTradeWindow as trigger select trigger.bidPriceCountAsDouble from LongLowBidIndicator where trigger.bidPriceCountAsDouble<LongLowBidIndicator.low")
    // runs
    // bar counter  @When("@Priority(3) on LongHighRunBarIndicator as trigger select trigger.high from LongHighRunBarIndicator")
    // counter @When("@Priority(3) on LongLowRunBarIndicator as trigger select trigger.low from LongLowRunBarIndicator")
    // trend ok@When("@Priority(3) on LastTradeWindow as trigger select trigger.priceCountAsDouble from LongHighTradeIndicator where trigger.priceCountAsDouble>LongHighTradeIndicator.high")
    //
    // public void handleEnterOrder(Market market, Object object, Object object2) {
    // TODO Auto-generated method stub

    //void handleLongHighIndicator(double interval, double scaleFactor, double d, ExecutionInstruction execInst, Market market, Market pairMarket) {
    public synchronized void handleEnterOrder(Market market, double entryPrice, double scaleFactor, double interval, ExecutionInstruction execInst,
            double forecast) {
        // TODO Auto-generated method stub

        if (!orderService.getTradingEnabled())
            return;
        Collection<Market> markets = new ArrayList<Market>();
        markets.add(market);

        for (Market tradedMarket : markets) {

            log.info("Order Entry Triggered at " + context.getTime() + "positionMap: "
                    + (positionMap.get(tradedMarket) == null ? "" : (positionMap.get(tradedMarket) == null ? "Null" : positionMap.get(tradedMarket).getType()))
                    + " book: " + quotes.getLastBook(tradedMarket) + " position "
                    + (getShortPosition(tradedMarket, interval) == null ? "Null" : getShortPosition(tradedMarket, interval).getOpenVolume()) + " positions "
                    + getPositions(tradedMarket));

        }
        if (positionMap.get(market) == null || positionMap.get(market).getType() != PositionType.ENTERING) {
            try {
                enterOrders(market, entryPrice, scaleFactor, interval, execInst, forecast);

            } catch (Exception ex) {
                revertPositionMap(market);
                log.error("Threw a Execption, full stack trace follows:", ex);
            }

        } else {
            revertPositionMap(market);
            log.info("Order Entry Triggered as already entering postion at " + context.getTime());
            return;
        }

    }

    void handleLongLowIndicator(double interval, double scaleFactor, double d, ExecutionInstruction execInst, Market market, Market pairMarket) {
        if (d == 0 || d == Double.MAX_VALUE || !orderService.getTradingEnabled())
            return;

        // if (lastShortEntryPrice != 0 && d >= lastShortEntryPrice)
        //   return;
        lastShortEntryPrice = d;
        //  synchronized (lock) {
        Collection<Market> markets = new ArrayList<Market>();
        markets.add(market);
        for (Market tradedMarket : markets) {
            log.info("Short Entry Triggered at " + context.getTime() + "positionMap: "
                    + (positionMap.get(tradedMarket) == null ? "" : (positionMap.get(tradedMarket) == null ? "Null" : positionMap.get(tradedMarket).getType()))
                    + " book: " + quotes.getLastBook(tradedMarket) + " position "
                    + (getLongPosition(tradedMarket, interval) == null ? "Null" : getLongPosition(tradedMarket, interval).getOpenVolume()) + " positions "
                    + getPositions(tradedMarket));
            //  if (orderService.getPendingOrders(portfolio) == null || orderService.getPendingOrders(portfolio).isEmpty())
            if (positionMap.get(tradedMarket) != null && getLongPosition(tradedMarket, interval).getOpenVolume().isPositive()) {

                log.info("Short Entry prevented as already have long position " + positionMap.get(tradedMarket).getPosition());

                handleShortLowIndicator(interval, d, ExecutionInstruction.TAKER, tradedMarket);
                revertPositionMap(tradedMarket);

                // return;
            }
        }

        if (positionMap.get(market) == null || positionMap.get(market).getType() != PositionType.ENTERING) {

            //  orderService.handleCancelAllOpeningSpecificOrders(portfolio, market);
            // we shpuld move this up
            try {
                enterShortOrders(interval, scaleFactor, d, execInst, market, pairMarket);
            } catch (Exception ex) {
                revertPositionMap(market);
                log.error("Threw a Execption, full stack trace follows:", ex);

                //  ex.getCause().printStackTrace();
            }
            /*  future = service.submit(new enterShortOrders(d, execInst));
              try {
                  //  if (future.isDone())
                  future.get();
              } catch (InterruptedException e) {
                  // TODO Auto-generated catch block
                  revertPositionMap(market);
                  log.error("Threw a Execption, full stack trace follows:", e);

                  e.printStackTrace();
                  Thread.currentThread().interrupt();

              } catch (ExecutionException ex) {
                  log.error("Threw a Execption, full stack trace follows:", ex);

                  revertPositionMap(market);
                  ex.getCause().printStackTrace();

              } catch (CancellationException ce) {
                  log.error("Threw a Execption, full stack trace follows:", ce);

                  revertPositionMap(market);
                  ce.getCause().printStackTrace();

              }*/
            //  startSignal.countDown();
            // endSignal.await();

        } else {
            log.info("Short Entry Prevented as already entering short position at " + context.getTime());

            return;
        }
        //new high price so we will enter a long trades
        //  
        //        //  synchronized (lock) {
        //        ArrayList<CommonOrderBuilder> orderList = buildEnterShortOrders(getATR());
        //        if (orderList == null)
        //            return;
        //        Iterator<OrderBuilder.CommonOrderBuilder> itob = orderList.iterator();
        //        while (itob.hasNext()) {
        //            OrderBuilder.CommonOrderBuilder orderBuilder = itob.next();
        //            placeOrder(orderBuilder);
        //
        //        }
        //  }
        //   }

        //		log.info("Portfolio: " + portfolio + " Total Value (" + portfolio.getBaseAsset() + "):"
        //				+ portfolioService.getCashBalance().plus(portfolioService.getMarketValue()) + " (Cash Balance:" + portfolioService.getCashBalance()
        //				+ " Open Trade Equity:" + portfolioService.getMarketValue() + ")");
    }

    //    private void placeOrder(CommonOrderBuilder orderBuilder) {
    //        Order entryOrder;
    //        if (orderBuilder != null) {
    //            entryOrder = orderBuilder.getOrder();
    //            log.info("Entering trade with order " + entryOrder);
    //            orderService.placeOrder(entryOrder);
    //
    //        }
    //    }

    private void placeOrder(Order order) {
        if (order != null) {
            log.info("Entering trade with order " + order);
            try {
                orderService.placeOrder(order);
            } catch (Throwable e) {
                // TODO Auto-generated catch block
                log.error(this.getClass().getSimpleName() + ":placeOrder Unable to place order " + order + ". Threw a Execption, full stack trace follows:", e);

            }

        }
    }

    @When("@Priority(9) select * from Transaction")
    public void handleTransaction(Transaction transaction) {
        log.debug(transaction.toString());
    }

    @When("@Priority(9) select * from PositionUpdate")
    void handlePositionUpdate(PositionUpdate positionUpdate) {
        // we are only string on postion in the map
        log.info("Position Update Recieved: " + positionUpdate);
        if (positionUpdate.getPosition() != null)
            updatePositionMap(positionUpdate.getMarket(), positionUpdate);
        else
            updatePositionMap(positionUpdate.getMarket(), positionUpdate);
        log.trace("Position Update processed: " + positionUpdate);
        // if (positionUpdate.getPosition() != null)
        // handleRebalance(positionUpdate.getPosition().getAvgPrice().asDouble());

    }

    // what about every time I get a postion change?

    //  @When("on pattern [every timer:interval(1 day)] select LastTradeWindow.priceCountAsDouble from LastTradeWindow")
    void handleRebalance(Market market, double d) {
        Amount cashBal = portfolioService.getAvailableBalance(market.getTradedCurrency(market), market.getExchange());

        if (cashBal.isZero())
            return;
        Amount transferQuote;
        Amount transferBase;
        Amount totalCost = DecimalAmount.ZERO;

        Offer bestBid = quotes.getLastBidForMarket(market);
        if (bestBid == null)
            return;
        Listing tradedListing = Listing.forPair(market.getTradedCurrency(market), portfolio.getBaseAsset());
        Offer tradedRate = quotes.getImpliedBestBidForListing(tradedListing);

        Position postion = getPosition(market, interval);
        if (!((postion == null || postion.getVolume() == null || postion.getVolume().isNegative() || postion.getVolume().isZero())))
            totalCost = (FeesUtil.getMargin(postion)).times(marginBuffer, Remainder.ROUND_EVEN);
        transferBase = cashBal.plus(totalCost);
        if (!transferBase.isZero()) {
            TransactionType baseTransferType = transferBase.isNegative() ? TransactionType.CREDIT : TransactionType.DEBIT;
            Amount baseTransferAmount = transferBase.isNegative() ? transferBase.negate() : transferBase.negate();
            TransactionType quoteTransferType = transferBase.isNegative() ? TransactionType.DEBIT : TransactionType.CREDIT;
            Amount quoteTransferAmount = transferBase.isNegative() ? transferBase : transferBase;
            //Amount cashBal = portfolioService.getAvailableBalance(market.getTradedCurrency());
            //neeed to transfer the total cost

            Transaction initialCredit = transactionFactory.create(portfolio, market.getExchange(), market.getTradedCurrency(market), baseTransferType,
                    baseTransferAmount, new DiscreteAmount(0, 0.01));
            context.setPublishTime(initialCredit);

            initialCredit.persit();

            context.route(initialCredit);

            Transaction initialDebit = transactionFactory.create(portfolio, market.getExchange(), portfolio.getBaseAsset(), quoteTransferType,
                    (quoteTransferAmount.times(tradedRate.getPrice(), Remainder.ROUND_EVEN)), new DiscreteAmount(0, 0.01));
            context.setPublishTime(initialDebit);

            initialDebit.persit();
            context.route(initialDebit);

        }

        //   log.info("rebalnce triggered");

    }

    //  @When("select * from Book(Book.market=TestStrategy.getMarket(),Book.bidVolumeAsDouble>0, Book.askVolumeAsDouble<0)")
    //@When("select talib(\"rsi\", askPriceAsDouble, 14) as value from Book")
    void handleMarketMaker(Market market, Book b) {

        Position postion = getPosition(market, interval);
        if ((postion == null || postion.getShortVolume() == null || postion.getLongVolume() == null || (postion.getShortVolume().isZero() && postion
                .getLongVolume().isZero())))
            return;
        // Amount OTEBal = portfolioService.getBaseUnrealisedPnL(market.getTradedCurrency());
        updateShortStops(quotes.getLastBidForMarket(market));
        updateLongStops(quotes.getLastAskForMarket(market));

        //log.info("RSI" + v);
        //        if (b.getMarket().equals(market)) {
        //            //	log.info("Market:" + b.getMarket() + " Price:" + b.getAskPriceCountAsDouble().toString() + " Timestamp" + b.getTime().toString());
        //
        //            List<Object> events = null;
        //            //log.info(b.getAskPrice().toString() + "Timestamp" + b.getTime().toString());
        //
        //            //log.info(b.getAskPriceCountAsDouble().toString());
        //            try {
        //                events = context.loadStatementByName("MOVING_AVERAGE");
        //                if (events.size() > 0) {
        //                    HashMap value = ((HashMap) events.get(events.size() - 1));
        //                    if (value.get("minValue") != null) {
        //                        double minDouble = (double) value.get("minValue");
        //                        double maxDouble = (double) value.get("maxValue");
        //                        long count = (long) value.get("countValue");
        //
        //                        log.info(b.getTime().toString() + "," + b.getAskPriceAsDouble().toString() + "," + String.valueOf(minDouble) + ","
        //                                + String.valueOf(maxDouble) + "," + String.valueOf(count));
        //                        //+ "Max Vlaue:" + maxDouble));
        //                        //return (trade.getPrice());
        //                    }
        //
        //                }
        //
        //            } catch (ParseException e1) {
        //                // TODO Auto-generated catch block
        //                e1.printStackTrace();
        //            } catch (DeploymentException e1) {
        //                // TODO Auto-generated catch block
        //                e1.printStackTrace();
        //            } catch (IOException e1) {
        //                // TODO Auto-generated catch block
        //                e1.printStackTrace();
        //            }
        //        }

        //			try {
        //				//events = context.loadStatementByName("MOVING_AVERAGE");
        //
        //				if (events.size() > 0) {
        //					//Trade trade = ((Trade) events.get(events.size() - 1));
        //					log.info("AvgPrice:" + events.get(events.size() - 1).toString());
        //
        //				}
        //			} catch (ParseException | DeploymentException | IOException e) {
        //				// TODO Auto-generated catch block
        //				e.printStackTrace();
        //			}
        //		if (b.getMarket().equals(market)) {
        //
        //			bestBid = b.getBestBid();
        //			bestAsk = b.getBestAsk();
        //			if (bestBid != null && bestAsk != null) {
        //				ready();
        //				enterTrade();
        //				exitTrade();
        //				log.info("Portfolio: " + portfolio + " Total Value (" + portfolio.getBaseAsset() + "):"
        //						+ portfolioService.getCashBalance().plus(portfolioService.getMarketValue()) + " (Cash Balance:" + portfolioService.getCashBalance()
        //						+ " Open Trade Equity:" + portfolioService.getMarketValue() + ")");
        //			}
        //		}
    }

    //    @Transient
    //    public Market getMarket() {
    //        return market;
    //    }

    //    public boolean updatePositionMap(Market market, PositionUpdate positionUpdate) {
    //
    //        ConcurrentHashMap<PositionType, Position> newPosition = new ConcurrentHashMap<PositionType, Position>();
    //        newPosition.put(type, position);
    //        positionMap.put(market, newPosition);
    //        return true;
    //
    //    }

    public boolean updateLongStops(Offer price) {
        //   Position position = getPosition(market);
        //    if (position == null || position.getLongVolume() == null || position.getLongVolume().isZero())
        //      return true;

        //DiscreteAmount stopAdjustment = new DiscreteAmount((long) (atrStop * getTradeATR(price.getMarket())), price.getMarket().getPriceBasis());

        //Amount exitPriceUpdate = (((OTEBal.times(maxLossTarget, Remainder.ROUND_EVEN)).dividedBy(
        //      position.getVolume().times(market.getContractSize(), Remainder.ROUND_EVEN), Remainder.ROUND_EVEN)).plus(position.getAvgPrice().invert()))
        //     .invert();
        //DiscreteAmount stopAdjustment = new DiscreteAmount((long) ((price.getPrice().minus(exitPriceUpdate)).dividedBy(price.getMarket().getPriceBasis(),
        //       Remainder.ROUND_EVEN)).asDouble(), price.getMarket().getPriceBasis());

        //need to remove this when running turtels
        // orderService.adjustLongStopLoss(price.getPrice(), stopAdjustment, forceStops, 0);

        return true;

    }

    public boolean updateShortStops(Offer price) {
        //   Position position = getPosition(market);
        //if (position == null || position.getShortVolume() == null || position.getShortVolume().isZero())
        //  return true;

        //    DiscreteAmount stopAdjustment = new DiscreteAmount((long) (atrStop * getTradeATR(price.getMarket())), price.getMarket().getPriceBasis());

        //Amount exitPriceUpdate = (((OTEBal.times(maxLossTarget, Remainder.ROUND_EVEN)).dividedBy(
        //  position.getVolume().times(market.getContractSize(), Remainder.ROUND_EVEN), Remainder.ROUND_EVEN)).plus(position.getAvgPrice().invert()))
        //  .invert();
        //DiscreteAmount stopAdjustment = new DiscreteAmount((long) ((price.getPrice().minus(exitPriceUpdate)).dividedBy(price.getMarket().getPriceBasis(),
        ///     Remainder.ROUND_EVEN)).asDouble(), price.getMarket().getPriceBasis());

        //need to remove this when running turtels
        //  orderService.adjustShortStopLoss(price.getPrice(), stopAdjustment, forceStops, 0);

        return true;

    }

    public boolean updateShortTarget(Position position, Offer price, Amount targetAdjustment) {
        //   Position position = getPosition(market);
        if (position == null || position.getShortVolume() == null || position.getShortVolume().isZero())
            return true;

        // DiscreteAmount targetAdjustment = new DiscreteAmount((long) (atrStop * getTradeATR()), price.getMarket().getPriceBasis());

        //Amount exitPriceUpdate = (((OTEBal.times(maxLossTarget, Remainder.ROUND_EVEN)).dividedBy(
        //      position.getVolume().times(market.getContractSize(), Remainder.ROUND_EVEN), Remainder.ROUND_EVEN)).plus(position.getAvgPrice().invert()))
        //     .invert();
        //DiscreteAmount stopAdjustment = new DiscreteAmount((long) ((price.getPrice().minus(exitPriceUpdate)).dividedBy(price.getMarket().getPriceBasis(),
        //       Remainder.ROUND_EVEN)).asDouble(), price.getMarket().getPriceBasis());

        //need to remove this when running turtels
        orderService.adjustShortTargetPrices(price.getPrice(), targetAdjustment, 0);

        return true;

    }

    public boolean updateLongTarget(Position position, Offer price, Amount targetAdjustment) {
        //   Position position = getPosition(market);
        if (position == null || position.getLongVolume() == null || position.getLongVolume().isZero())
            return true;
        // DiscreteAmount targetAdjustment = new DiscreteAmount((long) (atrStop * getTradeATR()), price.getMarket().getPriceBasis());

        //Amount exitPriceUpdate = (((OTEBal.times(maxLossTarget, Remainder.ROUND_EVEN)).dividedBy(
        //      position.getVolume().times(market.getContractSize(), Remainder.ROUND_EVEN), Remainder.ROUND_EVEN)).plus(position.getAvgPrice().invert()))
        //     .invert();
        //DiscreteAmount stopAdjustment = new DiscreteAmount((long) ((price.getPrice().minus(exitPriceUpdate)).dividedBy(price.getMarket().getPriceBasis(),
        //       Remainder.ROUND_EVEN)).asDouble(), price.getMarket().getPriceBasis());

        //need to remove this when running turtels
        orderService.adjustLongTargetPrices(price.getPrice(), targetAdjustment, 0);

        return true;

    }

    public synchronized Position getPosition(Market market, double orderGroup) {
        //    synchronized (lock) {
        // Need to get existing position
        // Position mergedPosition = new Position(portfolio, market.getExchange(), market, market.getTradedCurrency(), DecimalAmount.ZERO, DecimalAmount.ZERO);
        // if (positionMap.get(market) == null)
        //   return null; 
        if (positionMap == null || positionMap.get(market) == null)
            return null;
        return portfolio.getNetPosition(market.getBase(), market, orderGroup);
        //return portfolio.getPositions(market.getTradedCurrency(), market.getExchange());

        // positionMap.get(market).getPosition();

        //  portfolio.getNetPosition(market.getBase(), market);
        // if (positionType.equals(PositionType.ENTERING) || positionType.equals(PositionType.EXITING))
        //    return null;
        //  return (positionMap.get(market).getPosition());

    }

    public Position getLongPosition(Market market, double orderGroup) {
        //        //    synchronized (lock) {
        //        // Need to get existing position
        //        // Position mergedPosition = new Position(portfolio, market.getExchange(), market, market.getTradedCurrency(), DecimalAmount.ZERO, DecimalAmount.ZERO);
        //        // if (positionMap.get(market) == null)
        //        //   return null; 
        //        if (positionMap == null || positionMap.get(market) == null)
        //            return null;
        //
        // BTC/USD 
        //trading BTC/USD the traded CCY is USD
        // trading ETC(base)/BTC(quote), traded CCY is BTC
        // trading BTC/USD futures traded CCY is BTC.
        Asset tradedCCY = (market.getTradedCurrency(market) == null) ? market.getListing().getBase() : market.getTradedCurrency(market);
        // if (market.)
        return portfolio.getLongPosition(tradedCCY, market, orderGroup);
    }

    public Position getShortPosition(Market market, double orderGroup) {
        //        //    synchronized (lock) {
        //        // Need to get existing position
        //        // Position mergedPosition = new Position(portfolio, market.getExchange(), market, market.getTradedCurrency(), DecimalAmount.ZERO, DecimalAmount.ZERO);
        //        // if (positionMap.get(market) == null)
        //        //   return null; 
        //        if (positionMap == null || positionMap.get(market) == null)
        //            return null;
        //

        Asset tradedCCY = (market.getTradedCurrency(market) == null) ? market.getListing().getBase() : market.getTradedCurrency(market);
        return portfolio.getShortPosition(tradedCCY, market, orderGroup);
    }

    public Position getNetPosition(Market market, double orderGroup) {
        //    synchronized (lock) {
        // Need to get existing position
        // Position mergedPosition = new Position(portfolio, market.getExchange(), market, market.getTradedCurrency(), DecimalAmount.ZERO, DecimalAmount.ZERO);
        // if (positionMap.get(market) == null)
        //   return null; 
        //if (positionMap == null || positionMap.get(market) == null)
        //  return null;
        Asset tradedCCY = (market.getTradedCurrency(market) == null) ? market.getListing().getBase() : market.getTradedCurrency(market);

        return portfolio.getNetPosition(tradedCCY, market, orderGroup);
    }

    public Collection<Position> getPositions(Market market) {
        if (positionMap == null || positionMap.get(market) == null)
            return null;
        Asset tradedCCY = (market.getTradedCurrency(market) == null) ? market.getListing().getBase() : market.getTradedCurrency(market);

        return portfolio.getPositions(tradedCCY, market.getExchange());

    }

    // if (mergedPosition.)
    // return mergedPosition;
    //  }

    protected void revertPositionMap(Market market) {
        PositionType lastType = null;
        PositionType type = null;
        PositionUpdate update = positionMap.get(market);
        if (update != null) {
            lastType = update.getLastType();
            type = update.getType();

            if (lastType != null && (lastType == (PositionType.LONG) || lastType == (PositionType.SHORT)) && type != (PositionType.FLAT)) {
                update.setType(lastType);
                log.debug("set position map" + update + " from " + type + "to " + lastType);
            } else if (lastType == (PositionType.FLAT)) {
                log.debug("set position map" + update + " from " + type + "to " + PositionType.FLAT);
                update.setType(PositionType.FLAT);
            }
            //  if (type != null && (type == PositionType.LONG || type == PositionType.SHORT))
            //    update.setLastType(type);
        }
        // TODO Auto-generated method stub

    }

    protected void updatePositionMap(Market market, PositionType type) {
        PositionUpdate update = positionMap.get(market);
        PositionType lastType;
        if (update != null) {
            lastType = (update.getType() == (PositionType.ENTERING) || update.getType() == (PositionType.EXITING)) ? update.getLastType() : update.getType();

            //if last type is flat, long or short, we set it to the last type.
            update.setLastType(lastType);
            update.setType(type);
            log.debug("setting position map for: " + portfolio + " from " + lastType + " to " + type);
        } else {
            log.debug("setting position map for: " + portfolio + " from: " + PositionType.FLAT + " to: " + type);
            positionMap.put(market, new PositionUpdate(null, market, PositionType.FLAT, type));
        }

        // TODO Auto-generated method stub

    }

    public abstract double getAskATR(Market market);

    public abstract double getBidATR(Market market);

    @Transient
    public static Double getVolatilityInterval() {
        return volatilityInterval;
    }

    public abstract double getTradeATR(Tradeable market);

    @Transient
    public double getVol(Tradeable market, double interval) {
        List<Object> events = null;
        double vol = 0;
        try {
            events = context.loadStatementByName("GET_VOLATILITY");
            log.debug(this.getClass().getSimpleName() + ":getVol - Getting vol for " + market.getSymbol() + " with events " + events);
            if (events.size() > 0) {
                HashMap attibutes;
                //
                // ArrayList<HashMap> values = (ArrayList<HashMap>) events;
                //  Object HashMap;
                for (Object value : events) {
                    attibutes = ((HashMap) value);
                    if (attibutes.get("market").equals(market) && attibutes.get("interval").equals(interval)) {
                        HashMap keyValue = (HashMap) attibutes.get("vol");
                        log.debug(this.getClass().getSimpleName() + ":getVol - Getting  vol  for " + market.getSymbol() + " with keyValue "
                                + attibutes.get("atr"));

                        if (keyValue.get(market) != null) {
                            log.debug(this.getClass().getSimpleName() + ":getVol - Getting vol for " + market.getSymbol() + " with vol " + keyValue.get(market));

                            vol = (double) keyValue.get(market);

                            break;
                        }
                    }

                }

            }
        } catch (ParseException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (DeploymentException e1) {
            // TODO Auto-generated catch block
            log.error("Threw a Execption, full stack trace follows:", e1);

            e1.printStackTrace();
        } catch (IOException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        return vol;

    }

    @Transient
    public double getPriceVol(Market market, double interval) {
        List<Object> events = null;
        double vol = 0;
        try {
            events = context.loadStatementByName("GET_PRICE_VOLATILITY");
            log.debug(this.getClass().getSimpleName() + ":getPriceVol - Getting price vol for " + market.getSymbol() + " with events " + events);
            if (events.size() > 0) {
                HashMap attibutes;
                //
                // ArrayList<HashMap> values = (ArrayList<HashMap>) events;
                //  Object HashMap;
                for (Object value : events) {
                    attibutes = ((HashMap) value);
                    if (attibutes.get("market").equals(market) && attibutes.get("interval").equals(interval)) {
                        HashMap keyValue = (HashMap) attibutes.get("vol");
                        log.debug(this.getClass().getSimpleName() + ":getPriceVol - Getting price vol  for " + market.getSymbol() + " with keyValue "
                                + attibutes.get("atr"));

                        if (keyValue.get(market) != null) {
                            log.debug(this.getClass().getSimpleName() + ":getPriceVol - Getting price vol for " + market.getSymbol() + " with vol "
                                    + keyValue.get(market));

                            vol = (double) keyValue.get(market) * 100;

                            break;
                        }
                    }

                }

            }
        } catch (ParseException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (DeploymentException e1) {
            // TODO Auto-generated catch block
            log.error("Threw a Execption, full stack trace follows:", e1);

            e1.printStackTrace();
        } catch (IOException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        return vol;

    }

    @Transient
    public double getPricePointsVol(Market market, double interval) {
        List<Object> events = null;
        double vol = 0;
        try {
            events = context.loadStatementByName("GET_PRICEPOINTS_VOLATILITY");
            log.debug(this.getClass().getSimpleName() + ":getPricePointsVol - Getting price vol for " + market.getSymbol() + " with events " + events);
            if (events.size() > 0) {
                HashMap attibutes;
                //
                // ArrayList<HashMap> values = (ArrayList<HashMap>) events;
                //  Object HashMap;
                for (Object value : events) {
                    attibutes = ((HashMap) value);
                    if (attibutes.get("market").equals(market) && attibutes.get("interval").equals(interval)) {
                        HashMap keyValue = (HashMap) attibutes.get("vol");
                        log.debug(this.getClass().getSimpleName() + ":getPricePointsVol - Getting price vol  for " + market.getSymbol() + " with keyValue "
                                + attibutes.get("atr"));

                        if (keyValue.get(market) != null) {
                            log.debug(this.getClass().getSimpleName() + ":getPricePointsVol - Getting price vol for " + market.getSymbol() + " with vol "
                                    + keyValue.get(market));

                            vol = (double) keyValue.get(market);

                            break;
                        }
                    }

                }

            }
        } catch (ParseException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (DeploymentException e1) {
            // TODO Auto-generated catch block
            log.error("Threw a Execption, full stack trace follows:", e1);

            e1.printStackTrace();
        } catch (IOException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        return vol;

    }

    @Transient
    public double getShortLow(Market market, double interval) {
        List<Object> events = null;
        double vol = 0;
        try {
            events = context.loadStatementByName("GET_SHORT_LOW");
            log.debug(this.getClass().getSimpleName() + ":getShortLow - Getting low for " + market.getSymbol() + " with events " + events);
            if (events.size() > 0) {
                HashMap attibutes;
                //
                // ArrayList<HashMap> values = (ArrayList<HashMap>) events;
                //  Object HashMap;
                for (Object value : events) {
                    attibutes = ((HashMap) value);
                    if (attibutes.get("market").equals(market) && attibutes.get("interval").equals(interval)) {
                        vol = (double) attibutes.get("low");

                        break;
                    }
                }

            }
        } catch (ParseException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (DeploymentException e1) {
            // TODO Auto-generated catch block
            log.error("Threw a Execption, full stack trace follows:", e1);

            e1.printStackTrace();
        } catch (IOException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        return vol;

    }

    @Transient
    public double getShortHigh(Market market, double interval) {
        List<Object> events = null;
        double vol = 0;
        try {
            events = context.loadStatementByName("GET_SHORT_HIGH");
            log.debug(this.getClass().getSimpleName() + ":getShortHigh - Getting high for " + market.getSymbol() + " with events " + events);
            if (events.size() > 0) {
                HashMap attibutes;
                //
                // ArrayList<HashMap> values = (ArrayList<HashMap>) events;
                //  Object HashMap;
                for (Object value : events) {
                    attibutes = ((HashMap) value);
                    if (attibutes.get("market").equals(market) && attibutes.get("interval").equals(interval)) {
                        vol = (double) attibutes.get("high");

                        break;

                    }

                }

            }
        } catch (ParseException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (DeploymentException e1) {
            // TODO Auto-generated catch block
            log.error("Threw a Execption, full stack trace follows:", e1);

            e1.printStackTrace();
        } catch (IOException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        return vol;

    }

    @Transient
    public double getAvg(Market market) {
        List<Object> events = null;
        double avg = 0;
        try {
            events = context.loadStatementByName("GET_AVG_RANGE");
            log.debug(this.getClass().getSimpleName() + ":getAvg - Getting avg for " + market.getSymbol() + " with events " + events);
            if (events.size() > 0) {
                HashMap attibutes;
                //
                // ArrayList<HashMap> values = (ArrayList<HashMap>) events;
                //  Object HashMap;
                for (Object value : events) {
                    attibutes = ((HashMap) value);
                    if (attibutes.get("market").equals(market)) {
                        avg = (double) attibutes.get("avg");
                        break;
                    }
                }

            }

        } catch (ParseException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        } catch (DeploymentException e1) {
            // TODO Auto-generated catch block
            log.error("Threw a Execption, full stack trace follows:", e1);

            e1.printStackTrace();
        } catch (IOException e1) {
            log.error("Threw a Execption, full stack trace follows:", e1);

            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        return avg;

    }

    private boolean updatePositionMap(Market market, PositionUpdate update) {
        positionMap.put(market, update);
        return true;
        //        update
        //        // Need to get existing position
        //        if (positionMap.get(market) == null) {
        //            ConcurrentHashMap<PositionType, PositionUpdate> newPosition = new ConcurrentHashMap<PositionType, PositionUpdate>();
        //            Position position = new Position();
        //            newPosition.put(type, position);
        //            positionMap.put(market, update);
        //            return true;
        //        }
        //
        //        for (PositionType positionType : positionMap.get(market).keySet())
        //
        //        {
        //            ConcurrentHashMap<PositionType, Position> position = new ConcurrentHashMap<PositionType, Position>();
        //            position.put(type, positionMap.get(market).get(positionType));
        //            positionMap.put(market, position);
        //            return true;
        //        }
        //        return false;

    }

    public static void setPercentEquityRisked(double equityRisked) {
        percentEquityRisked = equityRisked;
    }

    public void setPositionInertia(double positionInertia) {
        this.positionInertia = positionInertia;
    }

    public void setVolatilityTarget(double volatilityTarget) {
        this.volatilityTarget = volatilityTarget;

    }

    public void setAtrStop(double atrStop) {
        this.atrStop = atrStop;

    }

    public void setMinSpread(double minSpread) {
        this.minSpread = minSpread;

    }

    public void setLastLongExitLimit(DiscreteAmount lastLongExitLimit) {
        this.lastLongExitLimit = lastLongExitLimit;
    }

    public void setLastShortExitLimit(DiscreteAmount lastShortExitLimit) {
        this.lastShortExitLimit = lastShortExitLimit;
    }

    public void setLimit(Boolean limit) {
        this.limit = limit;

    }

    public Boolean getLimit() {
        return limit;

    }

    public void setAtrTarget(double atrTarget) {
        this.atrTarget = atrTarget;

    }

    public void setTimeToLive(long timeToLive) {
        this.timeToLive = timeToLive;

    }

    public void setExitTimeToLive(long exitTimeToLive) {
        this.exitTimeToLive = exitTimeToLive;

    }

    public void setAtrTrigger(double atrTrigger) {
        this.atrTrigger = atrTrigger;

    }

    public void setSlipagePercentage(double slipagePercentage) {
        this.slippagePercentage = slipagePercentage;

    }

    public void setSlippage(double slippage) {
        this.slippage = slippage;

    }

    public void setTriggerBuffer(long triggerBuffer) {
        this.triggerBuffer = triggerBuffer;

    }

    public void setMaxLossTarget(double maxLoss) {
        this.maxLossTarget = maxLoss;

    }

    public void setVolatilityInterval(double volatilityInterval) {
        this.volatilityInterval = volatilityInterval;

    }

    @Transient
    public static double getPercentEquityRisked() {
        return percentEquityRisked;
    }

    @Transient
    public static double getPositionInertia() {
        return positionInertia;
    }

    @Transient
    private Amount getBaseLiquidatingValue() {
        Amount currentBal = portfolioService.getBaseCashBalance(portfolio.getBaseAsset()).plus(portfolioService.getBaseUnrealisedPnL(portfolio.getBaseAsset()));

        if (!balanceScalingAmount.isZero())
            // so if we have 20,0000 we need to normalise to 10,0000, so this means tkaing the cashbalace * fixBalanceAmount
            // (prevousBlance/cashBalance)* cashBalance
            return currentBal.minus(balanceScalingAmount);

        //     times(balanceScalingAmount, Remainder.ROUND_EVEN)
        //   .toBasis(portfolio.getBaseAsset().getBasis(), Remainder.ROUND_EVEN);
        return currentBal;
    }

    @Override
    @SuppressWarnings("ConstantConditions")
    protected OrderBuilder.CommonOrderBuilder buildEntryOrder(Market market) {
        Offer bestBid = quotes.getLastBidForMarket(market);

        if (bestBid == null)
            return null;
        DiscreteAmount limitPrice = bestBid.getPrice().decrement();
        // bUy 1% OF CASH BALANCE
        return order.create(context.getTime(), market, volumeCount, "Entry Order").withLimitPrice(limitPrice);
    }

    @SuppressWarnings("ConstantConditions")
    protected OrderBuilder.CommonOrderBuilder buildCashRebalanceOrder(Asset asset) {
        BigDecimal bal = portfolioService.getCashBalance(asset).asBigDecimal();
        long balCount = DiscreteAmount.roundedCountForBasis(bal, asset.getBasis());
        DiscreteAmount discreteBal = new DiscreteAmount(balCount, asset.getBasis());
        //
        Offer bestAsk = quotes.getLastAskForMarket(cashMarket);
        if (bestAsk == null || bestAsk.getPriceCount() == 0 || discreteBal.isNegative() || discreteBal.isZero())
            return null;
        DiscreteAmount limitPrice = bestAsk.getPrice().decrement(2);
        orderService.handleCancelAllSpecificOrders(portfolio, cashMarket);
        //orderService.handleCancelAllStopOrders(portfolio, market);
        //ArrayList<CommonOrderBuilder> orderList = new ArrayList<OrderBuilder.CommonOrderBuilder>();
        return (order.create(context.getTime(), cashMarket, discreteBal.negate(), "Rebalance Order").withLimitPrice(limitPrice));

        //return orderList;
    }

    public class handleCancelAllLongStopOrders implements Runnable {
        private final Portfolio portfolio;
        private final Market market;

        // protected Logger log;

        public handleCancelAllLongStopOrders(Portfolio portfolio, Market market) {
            this.portfolio = portfolio;
            this.market = market;

            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            //  if (!orderService.getPendingStopOrders(portfolio).isEmpty())
            orderService.handleCancelAllLongStopOrders(portfolio, market);
            if (positionMap.get(market).getType() == (PositionType.ENTERING) || positionMap.get(market).getType() == (PositionType.EXITING))

                revertPositionMap(market);

        }
    }

    public class handleCancelAllShortStopOrders implements Runnable {
        private final Portfolio portfolio;
        private final Market market;

        // protected Logger log;

        public handleCancelAllShortStopOrders(Portfolio portfolio, Market market) {
            this.portfolio = portfolio;
            this.market = market;

            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            //  if (!orderService.getPendingStopOrders(portfolio).isEmpty())
            orderService.handleCancelAllShortStopOrders(portfolio, market);
            if (positionMap.get(market).getType() == (PositionType.ENTERING) || positionMap.get(market).getType() == (PositionType.EXITING))

                revertPositionMap(market);

        }
    }

    class handleCancelAllSpecificOrders implements Runnable {
        private final Portfolio portfolio;
        private final Market market;

        // protected Logger log;

        public handleCancelAllSpecificOrders(Portfolio portfolio, Market market) {
            this.portfolio = portfolio;
            this.market = market;

            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            // if (!orderService.getPendingOrders(portfolio).isEmpty())
            orderService.handleCancelAllSpecificOrders(portfolio, market);
            if (positionMap.get(market).getType() == (PositionType.ENTERING) || positionMap.get(market).getType() == (PositionType.EXITING))
                revertPositionMap(market);

        }
    }

    class handleCancelSpecificOrder implements Runnable {
        private final SpecificOrder orderToCancel;

        // protected Logger log;

        public handleCancelSpecificOrder(SpecificOrder orderToCancel) {
            this.orderToCancel = orderToCancel;

            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            if (orderService.handleCancelSpecificOrder(orderToCancel))
                log.info("handelCancelSpecificOrder cancelled Specific Order:" + orderToCancel);
            else
                log.info("handelCancelSpecificOrder ubale to cancel Specific Order:" + orderToCancel);

            if (positionMap.get(orderToCancel.getMarket()).getType() == (PositionType.ENTERING)
                    || positionMap.get(orderToCancel.getMarket()).getType() == (PositionType.EXITING))
                revertPositionMap(orderToCancel.getMarket());
            //  if (!orderService.getPendingOrders(portfolio).isEmpty())

        }
    }

    class handleCancelAllClosingSpecificOrders implements Runnable {
        private final Portfolio portfolio;
        private final Market market;

        //  @Inject
        // protected transient OrderService orderService;

        // protected Logger log;

        public handleCancelAllClosingSpecificOrders(Portfolio portfolio, Market market) {
            this.portfolio = portfolio;
            this.market = market;

            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            // if (!orderService.getPendingCloseOrders(portfolio).isEmpty())
            orderService.handleCancelAllClosingSpecificOrders(portfolio, market);
            if (positionMap.get(market).getType() == (PositionType.ENTERING) || positionMap.get(market).getType() == (PositionType.EXITING))

                revertPositionMap(market);

        }
    }

    class handleCancelAllLongClosingSpecificOrders implements Runnable {
        private final Portfolio portfolio;
        private final Market market;
        private final ExecutionInstruction execInst;

        //  @Inject
        // protected transient OrderService orderService;

        // protected Logger log;

        public handleCancelAllLongClosingSpecificOrders(Portfolio portfolio, Market market, ExecutionInstruction execInst) {
            this.portfolio = portfolio;
            this.market = market;
            this.execInst = execInst;
            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            //if (!orderService.getPendingCloseOrders(portfolio).isEmpty())
            orderService.handleCancelAllLongClosingSpecificOrders(portfolio, market, execInst);
            if (positionMap.get(market).getType() == (PositionType.ENTERING) || positionMap.get(market).getType() == (PositionType.EXITING))

                revertPositionMap(market);

        }
    }

    class handleCancelAllShortClosingSpecificOrders implements Runnable {
        private final Portfolio portfolio;
        private final Market market;
        private final ExecutionInstruction execInst;

        //  @Inject
        // protected transient OrderService orderService;

        // protected Logger log;

        public handleCancelAllShortClosingSpecificOrders(Portfolio portfolio, Market market, ExecutionInstruction execInst) {
            this.portfolio = portfolio;
            this.market = market;
            this.execInst = execInst;

            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            // if (!orderService.getPendingCloseOrders(portfolio).isEmpty())
            orderService.handleCancelAllShortClosingSpecificOrders(portfolio, market, execInst);
            if (positionMap.get(market).getType() == (PositionType.ENTERING) || positionMap.get(market).getType() == (PositionType.EXITING))

                revertPositionMap(market);

        }
    }

    class handleCancelAllOpeningSpecificOrders implements Runnable {
        private final Portfolio portfolio;
        private final Market market;

        //  @Inject
        // protected transient OrderService orderService;

        // protected Logger log;

        public handleCancelAllOpeningSpecificOrders(Portfolio portfolio, Market market) {
            this.portfolio = portfolio;
            this.market = market;

            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            //if (!orderService.getPendingOpenOrders(portfolio).isEmpty())
            orderService.handleCancelAllOpeningSpecificOrders(portfolio, market);
            if (positionMap.get(market).getType() == (PositionType.ENTERING) || positionMap.get(market).getType() == (PositionType.EXITING))

                revertPositionMap(market);

        }
    }

    class handleCancelAllLongOpeningSpecificOrders implements Runnable {
        private final Portfolio portfolio;
        private final Market market;

        //  @Inject
        // protected transient OrderService orderService;

        // protected Logger log;

        public handleCancelAllLongOpeningSpecificOrders(Portfolio portfolio, Market market) {
            this.portfolio = portfolio;
            this.market = market;

            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            // if (!orderService.getPendingLongOpenOrders(portfolio).isEmpty())
            orderService.handleCancelAllLongOpeningSpecificOrders(portfolio, market);
            if (positionMap.get(market).getType() == (PositionType.ENTERING) || positionMap.get(market).getType() == (PositionType.EXITING))

                revertPositionMap(market);

        }
    }

    protected void createExits(Market market, Book b) {

        OrderState stopOrderState = null;
        Order orderStop1 = null;
        Order orderchk;
        Amount highAsk = null;
        Amount lowBid = null;
        DiscreteAmount limitPrice = null;
        for (Position position : getPositions(market)) {
            for (Fill positionFill : position.getFills()) {
                if (positionFill.isLong() && positionFill.getPositionEffect() == (PositionEffect.OPEN) && positionFill.getVolume().isPositive()) {
                    if (highAsk == null)
                        highAsk = positionFill.getPrice();
                    highAsk = (positionFill.getPrice().compareTo(highAsk) > 0) ? positionFill.getPrice() : highAsk;
                } else if (positionFill.isShort() && positionFill.getPositionEffect() == (PositionEffect.OPEN) && positionFill.getVolume().isNegative()) {
                    if (lowBid == null)
                        lowBid = positionFill.getPrice();
                    lowBid = (positionFill.getPrice().compareTo(lowBid) < 0) ? positionFill.getPrice() : lowBid;
                }

                ArrayList<Order> childOrders = new ArrayList<>();
                positionFill.getAllOrdersByParentFill(childOrders);

                for (Order stopOrder : childOrders) {
                    if (stopOrder instanceof GeneralOrder && stopOrder.getPositionEffect() == (PositionEffect.CLOSE) && stopOrder.getStopPrice() != null) {
                        stopOrderState = orderService.getOrderState(stopOrder);
                        if (stopOrderState == (OrderState.CANCELLED))
                            log.info("verify why is canclled");
                        orderStop1 = stopOrder;
                    }
                }
            }
        }
        for (Position position : getPositions(market)) {
            for (Fill positionFill : position.getFills()) {
                // if I want to sell cos I am long and prices are failling I won't get filled, if I want to buy as I am short and pricesing are rising I won't get filled.
                if (positionFill.getPositionType() == null || positionFill.getPositionType() == (PositionType.LONG)
                        || (positionFill.isLong() && orderService.getPendingLongCloseOrders(portfolio, ExecutionInstruction.TAKER, market).isEmpty())
                        || positionFill.getPositionType() == (PositionType.SHORT)
                        || (positionFill.isShort() && orderService.getPendingShortCloseOrders(portfolio, ExecutionInstruction.TAKER, market).isEmpty()))

                //&& 
                //   ((positionFill.isLong() && (lastLongExitLimit == null || lastLongExitLimit.compareTo(b.getAskPrice()) > 0)) || (positionFill.isShort() && (lastShortExitLimit == null || lastShortExitLimit
                //         .compareTo(b.getBidPrice()) < 0)))
                // && (positionFill.getOrder().getParentOrder() != null && !positionFill.getOrder().getParentOrder().getUnfilledVolume().isZero())
                {
                    //   log.info("exiting fill: " + positionFill);
                    updatePositionMap(market, PositionType.EXITING);
                    positionFill.setPositionType(PositionType.EXITING);
                    Order exitOrder = null;
                    Order cancelledStopOrder = null;
                    Collection<GeneralOrder> stopOrders = new ArrayList<GeneralOrder>();
                    Amount highestLongClosingLimit = null;
                    Amount lowestShortClosingLimit = null;
                    Collection<SpecificOrder> closingOrders = new ArrayList<SpecificOrder>();
                    ArrayList<Order> childOrders = new ArrayList<>();
                    positionFill.getAllOrdersByParentFill(childOrders);

                    for (Order stopOrder : childOrders) {
                        if (stopOrder instanceof GeneralOrder && stopOrder.getPositionEffect() == (PositionEffect.CLOSE)
                                && (stopOrder.getStopPrice() != null || stopOrder.getTargetPrice() != null) && orderService.getOrderState(stopOrder).isOpen()) {
                            stopOrders.add((GeneralOrder) stopOrder);
                        } else if (stopOrder instanceof SpecificOrder && stopOrder.getPositionEffect() == (PositionEffect.CLOSE)
                                && orderService.getOrderState(stopOrder).isOpen() && (stopOrder.getLimitPrice() != null && !stopOrder.getLimitPrice().isZero())) {
                            closingOrders.add((SpecificOrder) stopOrder);
                        }
                    }
                    if (closingOrders != null && !closingOrders.isEmpty()) {

                        revertPositionMap(market);
                        //  for (SpecificOrder closingOrder : closingOrders)

                        //    orderService.cancelOrder(closingOrder);

                        //continue;
                    }

                    //                for (SpecificOrder closingOrder : closingOrders) {
                    //                    if (highestLongClosingLimit == null)
                    //                        highestLongClosingLimit = closingOrder.getLimitPrice();
                    //
                    //                    highestLongClosingLimit = (closingOrder.getLimitPrice().compareTo(highestLongClosingLimit) > 0) ? closingOrder.getLimitPrice()
                    //                            : highestLongClosingLimit;
                    //                    if (position.isShort()) {
                    //                        if (lowestShortClosingLimit == null)
                    //                            lowestShortClosingLimit = closingOrder.getLimitPrice();
                    //                        lowestShortClosingLimit = (closingOrder.getLimitPrice().compareTo(lowestShortClosingLimit) < 0) ? closingOrder.getLimitPrice()
                    //                                : lowestShortClosingLimit;
                    //                    }
                    //
                    //                }
                    if (positionFill.getPositionEffect() == (PositionEffect.OPEN)) {

                        //     DiscreteAmount discreteVolume = positionFill.getOpenVolume().toBasis(market.getVolumeBasis(), Remainder.ROUND_EVEN);

                        //           if ((positionFill.isLong() && (highestLongClosingLimit == null || limitPrice.compareTo(highestLongClosingLimit) < 0)) ||

                        //              (positionFill.isShort() && (lowestShortClosingLimit == null || limitPrice.compareTo(lowestShortClosingLimit) > 0))) {

                        //if (closingOrders != null && !closingOrders.isEmpty())
                        //                      for (SpecificOrder closingOrder : closingOrders) {
                        //                          if (orderService.getOrderState(closingOrder).isOpen()) {
                        // we already have a working order out there for this fill, so lets move onto the next fill

                        //                             orderService.handleCancelSpecificOrder(closingOrder);
                        //revertPositionMap(market);
                        //continue positionLoop;
                        //                         }
                        //                      }

                        for (GeneralOrder stopOrder : stopOrders) {
                            stopOrderState = (orderService.getOrderState(stopOrder));
                            orderchk = orderService.getPendingTriggerOrder(stopOrder);
                            GeneralOrder longStopOrder = stopOrder;
                        }

                        GeneralOrder restingStopOrder = null;
                        if (stopOrders != null && !stopOrders.isEmpty()) {
                            for (GeneralOrder stopOrder : stopOrders) {
                                if (orderService.getOrderState(stopOrder).isOpen())
                                    restingStopOrder = stopOrder;
                                else {
                                    OrderState orderState = orderService.getOrderState(stopOrder);
                                    cancelledStopOrder = stopOrder;
                                }
                            }
                            if (restingStopOrder != null) {
                                // so why did this not update for 042409d9-fa04-4819-8c6d-cefb6f1fc818,
                                Amount exitVol = restingStopOrder.getUnfilledVolume().negate();
                                if (exitVol.isZero())
                                    continue;
                                Amount bidAsk = (position.isLong()) ? highAsk : lowBid;

                                //    b.getAskPrice() : b.getBidPrice();
                                long minSpreadAmount = (position.isLong()) ? (long) ((getAskATR(position.getMarket())) * minSpread)
                                        : (long) (getBidATR(position.getMarket()) * minSpread);

                                Amount commission = FeesUtil.getCommission(bidAsk, exitVol, position.getMarket(), PositionEffect.OPEN);
                                Amount breakEvenPrice = (position.isLong()) ? (bidAsk.invert().plus(commission.dividedBy(
                                        exitVol.times(market.getContractSize(market), Remainder.ROUND_EVEN), Remainder.ROUND_EVEN))).invert() : (lowBid
                                        .invert().minus(commission.dividedBy(exitVol.negate().times(market.getContractSize(market), Remainder.ROUND_EVEN),
                                        Remainder.ROUND_EVEN))).invert();
                                DiscreteAmount price = (position.isLong()) ? (new DiscreteAmount((breakEvenPrice.asBigDecimal().divide(
                                        BigDecimal.valueOf(market.getPriceBasis())).longValue()), market.getPriceBasis())).increment(minSpreadAmount)
                                        : (new DiscreteAmount((breakEvenPrice.asBigDecimal().divide(BigDecimal.valueOf(market.getPriceBasis())).longValue()),
                                                market.getPriceBasis())).decrement(minSpreadAmount);
                                // when exiting long want (ve) highest price, when exiting short want (-ve) lowest price

                                limitPrice = (position.isLong()) ? ((price.compareTo(b.getBestAsk().getPrice().decrement()) > 0) ? price : b.getBestAsk()
                                        .getPrice().decrement()) : (price.compareTo(b.getBestBid().getPrice().increment()) < 0) ? price : b.getBestBid()
                                        .getPrice().increment();

                                String comment = (exitVol.isPositive()) ? "Long Exit with resting stop" : "Short Exit with resting stop";

                                exitOrder = specificOrderFactory.create(context.getTime(), portfolio, market, exitVol.negate(), restingStopOrder, comment);

                                exitOrder.withLimitPrice(limitPrice).withPositionEffect(PositionEffect.CLOSE)
                                        .withExecutionInstruction(ExecutionInstruction.TAKER).withFillType(FillType.COMPLETED_CANCELS_OTHER)
                                        .withParentFill(positionFill);

                                restingStopOrder.setFillType(FillType.COMPLETED_CANCELS_OTHER);
                            } /*else if (cancelledStopOrder != null) {
                                OrderState orderState = orderService.getOrderState(cancelledStopOrder);
                                String comment = (positionFill.getOpenVolume().isPositive()) ? "Long Exit resubmitted stop" : "Short Exit resubmitted stop";

                                exitOrder = order.create(context.getTime(), market, positionFill.getOpenVolume().negate(), cancelledStopOrder, comment)
                                        .withLimitPrice(limitPrice).withPositionEffect(PositionEffect.CLOSE)
                                        .withExecutionInstruction(ExecutionInstruction.MAKER).withFillType(FillType.ONE_CANCELS_OTHER).getOrder();
                                exitOrder.setParentFill(positionFill);
                                positionFill.addChild(exitOrder);
                                cancelledStopOrder.setFillType(FillType.ONE_CANCELS_OTHER);
                              }*/
                        } /*else {
                            String comment = (positionFill.getOpenVolume().isPositive()) ? "Long Exit No Stop" : "Short Exit No Stop";

                            exitOrder = order.create(context.getTime(), market, positionFill.getOpenVolume().negate(), comment).withLimitPrice(limitPrice)
                                    .withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER).withFillType(FillType.LIMIT)
                                    .getOrder();
                            exitOrder.setParentFill(positionFill);
                            positionFill.addChild(exitOrder);
                            log.info("No active stop order from:" + positionFill.getChildren());
                          }*/

                        if (restingStopOrder == null && cancelledStopOrder != null) {
                            log.info("resubmitting stop order " + cancelledStopOrder + " for fill " + positionFill);
                            try {
                                orderService.placeOrder(cancelledStopOrder);
                            } catch (Throwable e) {
                                // TODO Auto-generated catch block
                                log.error(this.getClass().getSimpleName() + ":createExits Unable to place order " + cancelledStopOrder
                                        + ". Threw a Execption, full stack trace follows:", e);

                            }
                        }
                        if (exitOrder != null) {
                            //  if (positionFill.getPositionType() != PositionType.EXITING) {
                            log.info("Submitting new exit order " + exitOrder + " for fill " + positionFill + " at current bid:" + b.getBestBid() + " ask:"
                                    + b.getBestAsk());
                            log.info("Stop Order:" + stopOrderState);
                            try {
                                orderService.placeOrder(exitOrder);
                            } catch (Throwable e) {
                                // TODO Auto-generated catch block
                                log.error(this.getClass().getSimpleName() + ":createExits Unable to place order " + exitOrder
                                        + ". Threw a Execption, full stack trace follows:", e);

                            }
                            if (exitOrder.isAsk())
                                lastLongExitLimit = limitPrice;
                            else
                                lastShortExitLimit = limitPrice;
                        }

                        //  }

                    } else
                        positionFill.setPositionType(positionFill.getVolume().isPositive() ? PositionType.LONG : PositionType.SHORT);
                    revertPositionMap(market);
                }
            }
        }

    }

    public void createLongExit(double interval, Market market, Collection<SpecificOrder> longCloseOrders) {
        Book b = quotes.getLastBook(market);
        OrderState stopOrderState = null;
        Order orderStop1 = null;
        Order orderchk;
        Amount highAsk = null;
        Amount lowBid = null;
        DiscreteAmount limitPrice = null;
        Amount totalExitVol = DecimalAmount.ZERO;
        log.debug(this.getClass().getSimpleName() + " - createLongExit with interval " + interval + " market " + market + " closing long orders "
                + longCloseOrders);

        Collection<Fill> exitFills = new ArrayList<Fill>();
        if (getPositions(market) == null || !orderService.getPendingLongCloseOrders(portfolio, ExecutionInstruction.TAKER, market, interval).isEmpty()) {
            log.debug(this.getClass().getSimpleName() + ":createLongExit -  For interval " + interval + " incorrect Long position "
                    + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()) + " Pending close orders "
                    + orderService.getPendingLongCloseOrders(portfolio, ExecutionInstruction.TAKER, market, interval));

            return;
        }
        Collection<Order> stopTriggerOrders = orderService.getPendingLongStopOrders(portfolio, market, interval);

        Set<Order> failedStopOrders = new HashSet<Order>();
        for (Order order : stopTriggerOrders) {
            synchronized (order) {
                if (order.getParentFill() == null)
                    continue;

                // if I want to sell cos I am long and prices are failling I won't get filled, if I want to buy as I am short and pricesing are rising I won't get filled.
                if (order.getParentFill() != null && order.getParentFill().getPositionType() == (PositionType.LONG) || (order.getParentFill().isLong())) {
                    exitFills.add(order.getParentFill());
                    updatePositionMap(market, PositionType.EXITING);
                    order.getParentFill().setPositionType(PositionType.EXITING);
                    Order exitOrder = null;
                    Order cancelledStopOrder = null;
                    Collection<GeneralOrder> stopOrders = new ArrayList<GeneralOrder>();
                    Collection<SpecificOrder> closingOrders = new ArrayList<SpecificOrder>();
                    ArrayList<Order> childOrders = new ArrayList<>();
                    order.getParentFill().getAllOrdersByParentFill(childOrders);
                    //TODO why are their stop opers that are not present in the orderSerivce.getOrderState
                    for (Order stopOrder : childOrders) {
                        try {
                            if (stopOrder instanceof GeneralOrder && stopOrder.getPositionEffect() == (PositionEffect.CLOSE)
                                    && (stopOrder.getStopPrice() != null || stopOrder.getTargetPrice() != null)
                                    && orderService.getOrderState(stopOrder).isOpen() && stopOrder.getOrderGroup() == interval) {
                                stopOrders.add((GeneralOrder) stopOrder);
                            } else if (stopOrder instanceof SpecificOrder && stopOrder.getPositionEffect() == (PositionEffect.CLOSE)
                                    && orderService.getOrderState(stopOrder).isOpen()
                                    && (stopOrder.getLimitPrice() != null && !stopOrder.getLimitPrice().isZero()) && stopOrder.getOrderGroup() == interval) {
                                closingOrders.add((SpecificOrder) stopOrder);
                            }
                        } catch (Error | Exception ex) {
                            if (!stopOrder.getUnfilledVolume().isZero())
                                failedStopOrders.add(stopOrder);
                            continue;
                        }
                    }

                    if (order.getParentFill().getPositionEffect() == (PositionEffect.OPEN)) {
                        for (GeneralOrder stopOrder : stopOrders) {
                            try {
                                stopOrderState = (orderService.getOrderState(stopOrder));
                                orderchk = orderService.getPendingTriggerOrder(stopOrder);
                                GeneralOrder longStopOrder = stopOrder;
                            } catch (Error | Exception ex) {
                                if (!stopOrder.getUnfilledVolume().isZero())

                                    failedStopOrders.add(stopOrder);
                                continue;
                            }
                        }

                        GeneralOrder restingStopOrder = null;
                        if (stopOrders != null && !stopOrders.isEmpty()) {
                            for (GeneralOrder stopOrder : stopOrders) {
                                try {
                                    if (orderService.getOrderState(stopOrder).isOpen()) {
                                        restingStopOrder = stopOrder;
                                        totalExitVol = totalExitVol.plus(stopOrder.getUnfilledVolume().negate());
                                    } else {
                                        OrderState orderState = orderService.getOrderState(stopOrder);
                                        cancelledStopOrder = stopOrder;
                                    }
                                } catch (Error | Exception ex) {
                                    if (!stopOrder.getUnfilledVolume().isZero())

                                        failedStopOrders.add(stopOrder);
                                    continue;
                                }

                            }

                            if (restingStopOrder != null) {
                                // so why did this not update for 042409d9-fa04-4819-8c6d-cefb6f1fc818,
                                Amount exitVol = restingStopOrder.getUnfilledVolume().negate();
                                if (exitVol.isZero())
                                    continue;

                                String comment = (exitVol.isPositive()) ? interval + " Long Exit with resting stop" : interval
                                        + " Short Exit with resting stop";

                                exitOrder = specificOrderFactory.create(context.getTime(), portfolio, market, exitVol.negate(), restingStopOrder, comment);

                                exitOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER)
                                        .withFillType(FillType.COMPLETED_CANCELS_OTHER).withParentFill(order.getParentFill());
                                exitOrder.withOrderGroup(interval);
                                restingStopOrder.setFillType(FillType.COMPLETED_CANCELS_OTHER);
                            }
                        }
                        if (restingStopOrder == null && cancelledStopOrder != null) {
                            log.info("resubmitting  for interval " + interval + " stop order " + cancelledStopOrder + " for fill " + order.getParentFill());
                            try {
                                orderService.placeOrder(cancelledStopOrder);
                                totalExitVol = totalExitVol.plus(cancelledStopOrder.getUnfilledVolume().negate());
                            } catch (Throwable e) {
                                log.error(this.getClass().getSimpleName() + ":createExits  for interval " + interval + " unable to place order "
                                        + cancelledStopOrder + ". Threw a Execption, full stack trace follows:", e);

                            }
                        }
                        if (exitOrder != null) {
                            log.info("Submitting new exit order  for interval " + interval + " " + exitOrder + " for fill " + order.getParentFill()
                                    + " at current bid:" + b.getBestBid() + " ask:" + b.getBestAsk());
                            log.info("Stop Order:" + stopOrderState);
                            try {
                                orderService.placeOrder(exitOrder);
                            } catch (Throwable e) {
                                log.error(this.getClass().getSimpleName() + ":createExits  for interval " + interval + " unable to place order " + exitOrder
                                        + ". Threw a Execption, full stack trace follows:", e);

                            }
                            continue;

                        }

                    } else
                        order.getParentFill().setPositionType(order.getParentFill().getVolume().isPositive() ? PositionType.LONG : PositionType.SHORT);
                    revertPositionMap(market);
                }

            }
        }

        // let's see if we can exit the quntity for the failed long stop orders
        if (failedStopOrders != null && !failedStopOrders.isEmpty()) {
            Collection<Fill> exitedFills = new ArrayList<Fill>();

            Amount totalExitVolume = DecimalAmount.ZERO;
            Amount totalWorkingVolume = DecimalAmount.ZERO;
            for (Order workingOrder : longCloseOrders) {
                if (workingOrder.getOrderGroup() == interval)
                    totalWorkingVolume = totalWorkingVolume.plus(workingOrder.getUnfilledVolume());

            }
            for (Order failedOrder : failedStopOrders) {
                if (failedOrder.getOrderGroup() == interval) {
                    exitedFills.add(failedOrder.getParentFill());
                    //       failedOrder.getParentFill().setPositionType(PositionType.EXITING);

                    totalExitVolume = totalExitVolume.plus(failedOrder.getUnfilledVolume());
                }

            }
            totalExitVolume = totalExitVolume.minus(totalWorkingVolume);

            // so if failed ostops with over zero quanity or the long postion is zero or positive, i have a long position
            if (!totalExitVolume.isZero() && getLongPosition(market, interval).getOpenVolume().isPositive()) {
                log.info(this.getClass().getSimpleName() + ": createLongExit - For interval " + interval + " exit Volume: " + totalExitVolume
                        + " long position volume " + getLongPosition(market, interval).getOpenVolume() + " for position " + getLongPosition(market, interval));

                //   Offer bestBid = quotes.getLastBook(market).getBestBidByVolume(
                //         new DiscreteAmount(DiscreteAmount.roundedCountForBasis(totalExitVolume.asBigDecimal(), market.getVolumeBasis()), market
                //               .getVolumeBasis()));
                // this is short exit, so I am buy, so hitting the ask
                // loop down asks until the total quanity of the order is reached.
                // limitPrice = bestBid.getPrice().decrement(slippage);

                String comment = interval + " Long Failed Stop Exit without resting stop";
                SpecificOrder failedStopExitOrder = specificOrderFactory.create(context.getTime(), portfolio, market, totalExitVolume, comment);

                failedStopExitOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER).withOrderGroup(interval);
                //.withLimitPrice(limitPrice)
                //      .withTimeToLive(exitTimeToLive);
                log.info("submiting postion long exit for interval " + interval + " " + failedStopExitOrder + " for failed stop orders of quanity "
                        + totalExitVolume);
                try {
                    orderService.placeOrder(failedStopExitOrder);
                    for (Fill exitedFill : exitedFills)
                        exitedFill.setPositionType(PositionType.EXITING);
                } catch (Throwable e) {
                    // TODO Auto-generated catch block
                    log.error(this.getClass().getSimpleName() + ":createLongExit for interval " + interval + " unable to place order " + failedStopExitOrder
                            + ". Threw a Execption, full stack trace follows:", e);

                }

            }
        }
        // so if all of my stops have filed, I will exit postion
        // if some of the stops of failed then the above should take case or it

        stopTriggerOrders.removeAll(failedStopOrders);

        // All Order have been triggered, but the position is not close yet, But each fill in the posiotn is exiting so we should check that.
        if (!getLongPosition(market, interval).getOpenVolume().isPositive()) {
            log.info("submitting long exit for interval " + interval + " prevented for position  " + getLongPosition(market, interval)
                    + " for position  as postion map is " + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()));

            revertPositionMap(market);
            return;
        }

        if (stopTriggerOrders == null || stopTriggerOrders.isEmpty() || exitFills.isEmpty()) {
            if (positionMap.get(market) == null || positionMap.get(market).getType() == null
                    || (positionMap.get(market).getType() != PositionType.EXITING || positionMap.get(market).getType() != PositionType.FLAT))
                updatePositionMap(market, PositionType.EXITING);
            Amount exitVol = DecimalAmount.ZERO;
            Amount totalWorkingVolume = DecimalAmount.ZERO;

            boolean openChildOrder = false;
            Collection<Fill> exitedFills = new ArrayList<Fill>();
            for (Fill exitFill : getLongPosition(market, interval).getFills()) {
                if (exitFill.getOrder().getOrderGroup() == interval
                        && exitFill.getPositionEffect().equals(PositionEffect.OPEN)
                        && exitFill.getOpenVolume().isPositive()

                        && ((!exitFill.getPositionType().equals(PositionType.EXITING) && orderService.getPendingLongCloseOrders(portfolio, market).isEmpty()) || !exitFill
                                .getPositionType().equals(PositionType.EXITING))) {

                    openChildOrder = false;
                    for (Order childOrder : exitFill.getFillChildOrders())
                        if (orderService.getOrderState(childOrder).isOpen())
                            openChildOrder = true;
                    if (!openChildOrder) {
                        exitedFills.add(exitFill);

                        exitVol = exitVol.plus(exitFill.getOpenVolume());
                    }
                }
            }
            for (Order workingOrder : longCloseOrders) {
                if (workingOrder.getOrderGroup() == interval)
                    totalWorkingVolume = totalWorkingVolume.plus(workingOrder.getUnfilledVolume());

            }
            exitVol = exitVol.minus(totalWorkingVolume);

            if (exitVol.isZero() || exitVol.minus(totalExitVol).isZero() || getLongPosition(market, interval).getOpenVolume().isNegative()) {
                log.info("submitting long exit for interval " + interval + " prevented for position with " + exitVol + " totalEixtVol " + totalExitVol
                        + "for position" + getLongPosition(market, interval) + " for position  as postion map is "
                        + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()));
                revertPositionMap(market);
                return;
            }
            //else if (getShortPosition(market).getOpenVolume().isPositive())
            //  createLongExit(b);
            // I am buying so I can buy at
            //   asdfasdfasd
            //      Offer bestBid = quotes.getLastBook(market).getBestBidByVolume(
            //            new DiscreteAmount(DiscreteAmount.roundedCountForBasis(exitVol.negate().asBigDecimal(), market.getVolumeBasis()), market.getVolumeBasis()));
            // this is log exit exit, so I am selling, so hitting the bid
            // loop down bid until the total quanity of the order is reached.
            //  limitPrice = bestBid.getPrice().decrement(slippage);

            //  limitPrice = b.getBestAsk().getPrice().decrement();
            log.info("submitting long exit for interval " + interval + " order without testing stop for exitVol " + exitVol + " totalEixtVol" + totalExitVol
                    + "for position" + getLongPosition(market, interval) + " for position  as postion map is "
                    + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()));

            String comment = interval + " Long Position Exit without resting stop";

            SpecificOrder positionExitOrder = specificOrderFactory.create(context.getTime(), portfolio, market, exitVol.negate(), comment);

            positionExitOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER).withOrderGroup(interval);
            log.info("submiting postion long exit " + positionExitOrder + " for position " + getLongPosition(market, interval));
            try {
                orderService.placeOrder(positionExitOrder);
                for (Fill exitedFill : exitedFills)
                    exitedFill.setPositionType(PositionType.EXITING);

            } catch (Throwable e) {
                log.error(this.getClass().getSimpleName() + ":createLongExit for interval " + interval + " Unable to place order " + positionExitOrder
                        + ". Threw a Execption, full stack trace follows:", e);

            }
            return;
        } else if (getLongPosition(market, interval).getOpenVolume().abs().compareTo(totalExitVol.abs()) > 0) {

            Amount exitVol = DecimalAmount.ZERO;
            Amount totalWorkingVolume = DecimalAmount.ZERO;
            Collection<Fill> exitedFills = new ArrayList<Fill>();

            for (Fill exitFill : getLongPosition(market, interval).getFills()) {
                if (exitFill.getOrder().getOrderGroup() == interval && exitFill.getPositionType() != PositionType.EXITING
                        && !exitFill.getUnfilledVolume().isZero()) {
                    exitVol = exitVol.plus(exitFill.getUnfilledVolume());
                    exitedFills.add(exitFill);
                }
            }
            for (Order workingOrder : longCloseOrders) {
                if (workingOrder.getOrderGroup() == interval)
                    totalWorkingVolume = totalWorkingVolume.plus(workingOrder.getUnfilledVolume());

            }
            exitVol = exitVol.minus(totalWorkingVolume);

            if (exitVol.isZero() || exitVol.isNegative())
                return;

            log.info("submitting long exit order for interval " + interval + " without resting stops or working orders for exitVol " + exitVol
                    + " totalEixtVol" + totalExitVol + "for position" + getLongPosition(market, interval) + " for position  as postion map is "
                    + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()));

            String comment = interval + " Long Position Exit without working stops";

            SpecificOrder positionExitOrder = specificOrderFactory.create(context.getTime(), portfolio, market, exitVol.negate(), comment);

            positionExitOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER).withOrderGroup(interval);
            log.info("submiting postion long exit for interval " + interval + " " + positionExitOrder + " for position " + getLongPosition(market, interval));
            try {
                orderService.placeOrder(positionExitOrder);
                for (Fill exitedFill : exitedFills)
                    exitedFill.setPositionType(PositionType.EXITING);
            } catch (Throwable e) {
                log.error(this.getClass().getSimpleName() + ":createLongExit for interval " + interval + " Unable to place order " + positionExitOrder
                        + ". Threw a Execption, full stack trace follows:", e);

            }
            return;

        }
    }

    public void createShortExit(double interval, Market market, Collection<SpecificOrder> shortCloseOrders) {
        //TODO If part of the fills have stops and part don't, we need to exit all a the same time
        Book b = quotes.getLastBook(market);

        OrderState stopOrderState = null;
        Collection<Fill> exitFills = new ArrayList<Fill>();
        Amount totalExitVol = DecimalAmount.ZERO;

        Order orderStop1 = null;
        Order orderchk;
        Amount highAsk = null;
        Amount lowBid = null;
        DiscreteAmount limitPrice = null;
        log.debug(this.getClass().getSimpleName() + " - createShortExit with interval " + interval + " market " + market + " closing long orders "
                + shortCloseOrders);

        if (getPositions(market) == null || !orderService.getPendingShortCloseOrders(portfolio, ExecutionInstruction.TAKER, market, interval).isEmpty()) {
            log.debug(this.getClass().getSimpleName() + ":createShortExit - For interval " + interval + " incorrect Short position "
                    + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()) + " Pending close orders "
                    + orderService.getPendingShortCloseOrders(portfolio, ExecutionInstruction.TAKER, market, interval));

            return;
        }
        Set<Order> failedStopOrders = new HashSet<Order>();

        Collection<Order> stopTriggerOrders = orderService.getPendingShortStopOrders(portfolio, market, interval);
        for (Order order : stopTriggerOrders) {
            synchronized (order) {
                if (order.getParentFill() == null)
                    continue;
                // if I want to sell cos I am long and prices are failling I won't get filled, if I want to buy as I am short and pricesing are rising I won't get filled.
                if (order.getParentFill() != null && order.getParentFill().getPositionType() == (PositionType.SHORT) || (order.getParentFill().isShort())) {
                    exitFills.add(order.getParentFill());
                    updatePositionMap(market, PositionType.EXITING);
                    order.getParentFill().setPositionType(PositionType.EXITING);
                    Order exitOrder = null;
                    Order cancelledStopOrder = null;
                    Collection<GeneralOrder> stopOrders = new ArrayList<GeneralOrder>();
                    Collection<SpecificOrder> closingOrders = new ArrayList<SpecificOrder>();
                    ArrayList<Order> childOrders = new ArrayList<>();
                    order.getParentFill().getAllOrdersByParentFill(childOrders);

                    for (Order stopOrder : childOrders) {
                        try {
                            if (stopOrder instanceof GeneralOrder && stopOrder.getPositionEffect() == (PositionEffect.CLOSE)
                                    && (stopOrder.getStopPrice() != null || stopOrder.getTargetPrice() != null)
                                    && orderService.getOrderState(stopOrder).isOpen()) {
                                stopOrders.add((GeneralOrder) stopOrder);
                            } else if (stopOrder instanceof SpecificOrder && stopOrder.getPositionEffect() == (PositionEffect.CLOSE)
                                    && orderService.getOrderState(stopOrder).isOpen()
                                    && (stopOrder.getLimitPrice() != null && !stopOrder.getLimitPrice().isZero())) {
                                closingOrders.add((SpecificOrder) stopOrder);
                            }
                        } catch (Error | Exception ex) {
                            if (!stopOrder.getUnfilledVolume().isZero())
                                failedStopOrders.add(stopOrder);
                            continue;
                        }
                    }

                    if (order.getParentFill().getPositionEffect() == (PositionEffect.OPEN)) {
                        for (GeneralOrder stopOrder : stopOrders) {
                            try {
                                stopOrderState = (orderService.getOrderState(stopOrder));
                                orderchk = orderService.getPendingTriggerOrder(stopOrder);
                                GeneralOrder longStopOrder = stopOrder;
                            } catch (Error | Exception ex) {
                                if (!stopOrder.getUnfilledVolume().isZero())

                                    failedStopOrders.add(stopOrder);
                                continue;
                            }
                        }

                        GeneralOrder restingStopOrder = null;
                        if (stopOrders != null && !stopOrders.isEmpty()) {
                            for (GeneralOrder stopOrder : stopOrders) {
                                try {
                                    if (orderService.getOrderState(stopOrder).isOpen()) {
                                        restingStopOrder = stopOrder;
                                        totalExitVol = totalExitVol.plus(stopOrder.getUnfilledVolume().negate());
                                    }

                                    else {
                                        OrderState orderState = orderService.getOrderState(stopOrder);
                                        cancelledStopOrder = stopOrder;
                                    }
                                } catch (Error | Exception ex) {
                                    if (!stopOrder.getUnfilledVolume().isZero())

                                        failedStopOrders.add(stopOrder);
                                    continue;
                                }
                            }
                            if (restingStopOrder != null) {
                                // so why did this not update for 042409d9-fa04-4819-8c6d-cefb6f1fc818,
                                Amount exitVol = restingStopOrder.getUnfilledVolume().negate();
                                if (exitVol.isZero())
                                    continue;

                                // need to set limit price to the lowest price I can sell/highest price I can buy for for given quanity
                                // DiscreteAmount.roundedCountForBasis(totalExitVol.asBigDecimal(), market.getVolumeBasis());
                                //  Offer bestAsk = quotes.getLastBook(market).getBestAskByVolume(
                                //        new DiscreteAmount(DiscreteAmount.roundedCountForBasis(totalExitVol.asBigDecimal(), market.getVolumeBasis()), market
                                //              .getVolumeBasis()));
                                // this is short exit, so I am buy, so hitting the ask
                                // loop down asks until the total quanity of the order is reached.
                                //  limitPrice = bestAsk.getPrice().increment(slippage);

                                String comment = (exitVol.isPositive()) ? interval + " Long Exit with resting stop" : interval
                                        + " Short Exit with resting stop";
                                exitOrder = specificOrderFactory.create(context.getTime(), portfolio, market, exitVol.negate(), restingStopOrder, comment);

                                exitOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER)
                                        .withFillType(FillType.COMPLETED_CANCELS_OTHER).withParentFill(order.getParentFill()).withOrderGroup(interval);

                                restingStopOrder.setFillType(FillType.COMPLETED_CANCELS_OTHER);
                            }
                        }
                        if (restingStopOrder == null && cancelledStopOrder != null) {
                            log.info("resubmitting for interval " + interval + " stop order " + cancelledStopOrder + " for fill " + order.getParentFill());
                            try {
                                orderService.placeOrder(cancelledStopOrder);
                                totalExitVol = totalExitVol.plus(cancelledStopOrder.getUnfilledVolume().negate());
                            } catch (Throwable e) {
                                // TODO Auto-generated catch block
                                log.error(this.getClass().getSimpleName() + ":createExits for interval " + interval + " Unable to place order "
                                        + cancelledStopOrder + ". Threw a Execption, full stack trace follows:", e);

                            }

                        }
                        if (exitOrder != null) {
                            log.info("Submitting new exit order  for interval " + interval + " " + exitOrder + " for fill " + order.getParentFill()
                                    + " at current bid:" + b.getBestBid() + " ask:" + b.getBestAsk());
                            log.info("Stop Order:" + stopOrderState);
                            try {
                                orderService.placeOrder(exitOrder);
                            } catch (Throwable e) {
                                log.error(this.getClass().getSimpleName() + ":createExits for interval " + interval + " unable to place order " + exitOrder
                                        + ". Threw a Execption, full stack trace follows:", e);

                            }
                            continue;

                        }

                    } else
                        order.getParentFill().setPositionType(order.getParentFill().getVolume().isPositive() ? PositionType.LONG : PositionType.SHORT);
                    revertPositionMap(market);
                }
            }
        }

        // let's see if we can exit the quntity for the failed long stop orders
        if (failedStopOrders != null && !failedStopOrders.isEmpty()) {
            Collection<Fill> exitedFills = new ArrayList<Fill>();
            Amount totalExitVolume = DecimalAmount.ZERO;
            Amount totalWorkingVolume = DecimalAmount.ZERO;
            for (Order workingOrder : shortCloseOrders) {
                if (workingOrder.getOrderGroup() == interval)
                    totalWorkingVolume = totalWorkingVolume.plus(workingOrder.getUnfilledVolume());

            }
            for (Order failedOrder : failedStopOrders) {
                if (failedOrder.getOrderGroup() == interval) {
                    exitedFills.add(failedOrder.getParentFill());
                    totalExitVolume = totalExitVolume.plus(failedOrder.getUnfilledVolume());
                }

            }
            totalExitVolume = totalExitVolume.minus(totalWorkingVolume);

            // so if failed ostops with over zero quanity or the short postion is zero or negaitive, i have a short position, shoudl this be and?

            if (!totalExitVolume.isZero() && getShortPosition(market, interval).getOpenVolume().isNegative()) {
                log.info(this.getClass().getSimpleName() + ": createShortExit -  For interval " + interval + " exit Volume: " + totalExitVolume
                        + " short position volume " + getShortPosition(market, interval).getOpenVolume() + " for position "
                        + getShortPosition(market, interval));

                // limitPrice = b.getBestAsk().getPrice().decrement();

                //Offer bestAsk = quotes.getLastBook(market).getBestAskByVolume(
                //      new DiscreteAmount(DiscreteAmount.roundedCountForBasis(totalExitVolume.asBigDecimal(), market.getVolumeBasis()), market
                //            .getVolumeBasis()));
                // this is short exit, so I am buy, so hitting the ask
                // loop down asks until the total quanity of the order is reached.
                //limitPrice = bestAsk.getPrice().increment(slippage);

                String comment = interval + " Short Failed Stop Exit without resting stop";
                SpecificOrder failedStopExitOrder = specificOrderFactory.create(context.getTime(), portfolio, market, totalExitVolume, comment);

                failedStopExitOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER).withOrderGroup(interval);
                log.info("submiting postion short exit for interval " + interval + " " + failedStopExitOrder + " for failed stop orders of quanity "
                        + totalExitVolume);
                try {
                    orderService.placeOrder(failedStopExitOrder);
                    for (Fill exitedFill : exitedFills)
                        exitedFill.setPositionType(PositionType.EXITING);
                } catch (Throwable e) {
                    log.error(this.getClass().getSimpleName() + ":createShortExit for interval " + interval + " unable to place order " + failedStopExitOrder
                            + ". Threw a Execption, full stack trace follows:", e);

                }

            }
        }
        stopTriggerOrders.removeAll(failedStopOrders);
        if (!getShortPosition(market, interval).getOpenVolume().isNegative()) {
            log.info("submitting short exit prevented for position  for interval " + interval + " " + getShortPosition(market, interval)
                    + " for position  as postion map is " + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()));

            revertPositionMap(market);
            return;
        }
        if (stopTriggerOrders == null || stopTriggerOrders.isEmpty() || exitFills.isEmpty()) {
            if (positionMap.get(market) == null || positionMap.get(market).getType() == null
                    || (positionMap.get(market).getType() != PositionType.EXITING || positionMap.get(market).getType() != PositionType.FLAT))
                updatePositionMap(market, PositionType.EXITING);

            Amount exitVol = DecimalAmount.ZERO;
            Amount totalWorkingVolume = DecimalAmount.ZERO;
            boolean openChildOrder = false;
            Collection<Fill> exitedFills = new ArrayList<Fill>();

            for (Fill exitFill : getShortPosition(market, interval).getFills()) {
                if (exitFill.getOrder().getOrderGroup() == interval
                        && exitFill.getPositionEffect().equals(PositionEffect.OPEN)
                        && exitFill.getOpenVolume().isNegative()
                        && ((!exitFill.getPositionType().equals(PositionType.EXITING) && orderService.getPendingShortCloseOrders(portfolio, market).isEmpty()) || !exitFill
                                .getPositionType().equals(PositionType.EXITING))) {
                    openChildOrder = false;
                    for (Order childOrder : exitFill.getFillChildOrders())
                        if (orderService.getOrderState(childOrder).isOpen())
                            openChildOrder = true;
                    if (!openChildOrder) {
                        exitedFills.add(exitFill);
                        exitVol = exitVol.plus(exitFill.getOpenVolume());
                    }
                }
            }
            for (Order workingOrder : shortCloseOrders) {
                if (workingOrder.getOrderGroup() == interval)
                    totalWorkingVolume = totalWorkingVolume.plus(workingOrder.getUnfilledVolume());

            }
            exitVol = exitVol.minus(totalWorkingVolume);
            // when we cancel the order, need to set parent fill of order back to long/short
            if (exitVol.isZero() || exitVol.minus(totalExitVol).isZero() || getLongPosition(market, interval).getOpenVolume().isNegative()) {
                log.info("submitting short exit for interval " + interval + " prevented for exitVol " + exitVol + " totalEixtVol" + totalExitVol
                        + "for position" + getShortPosition(market, interval) + " for position  as postion map is "
                        + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()));

                revertPositionMap(market);
                return;
            }

            log.info("submitting short for interval " + interval + " exit order withou testing stop for exitVol " + exitVol + " totalEixtVol" + totalExitVol
                    + "for position" + getShortPosition(market, interval) + " for position  as postion map is "
                    + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()));

            String comment = interval + " Short Position Exit without resting stop";

            SpecificOrder positionExitOrder = specificOrderFactory.create(context.getTime(), portfolio, market, exitVol.negate(), comment);

            positionExitOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER).withOrderGroup(interval);
            log.info("submiting postion short exit " + positionExitOrder + " for position " + getShortPosition(market, interval));
            try {
                orderService.placeOrder(positionExitOrder);
                for (Fill exitedFill : exitedFills)
                    exitedFill.setPositionType(PositionType.EXITING);
            } catch (Throwable e) {
                log.error(this.getClass().getSimpleName() + ":createShortExit for interval " + interval + " unable to place order " + positionExitOrder
                        + ". Threw a Execption, full stack trace follows:", e);

            }
            return;
        } else if (getShortPosition(market, interval).getOpenVolume().abs().compareTo(totalExitVol.abs()) > 0) {
            Amount exitVol = DecimalAmount.ZERO;
            Amount totalWorkingVolume = DecimalAmount.ZERO;
            Collection<Fill> exitedFills = new ArrayList<Fill>();

            for (Fill exitFill : getLongPosition(market, interval).getFills()) {
                if (exitFill.getOrder().getOrderGroup() == interval && exitFill.getPositionType() != PositionType.EXITING
                        && !exitFill.getUnfilledVolume().isZero()) {
                    exitVol = exitVol.plus(exitFill.getUnfilledVolume());
                    exitedFills.add(exitFill);
                }
            }
            for (Order workingOrder : shortCloseOrders) {
                if (workingOrder.getOrderGroup() == interval)
                    totalWorkingVolume = totalWorkingVolume.plus(workingOrder.getUnfilledVolume());

            }
            exitVol = exitVol.minus(totalWorkingVolume);

            if (exitVol.isZero() || exitVol.isPositive())
                return;

            log.info("submitting short exit order for interval " + interval + " without resting stops or working orders for exitVol " + exitVol
                    + " totalEixtVol" + totalExitVol + "for position" + getShortPosition(market, interval) + " for position  as postion map is "
                    + (positionMap.get(market) == null ? "Null" : positionMap.get(market).getType()));

            String comment = interval + " Short Position Exit without working stops";

            SpecificOrder positionExitOrder = specificOrderFactory.create(context.getTime(), portfolio, market, exitVol.negate(), comment);

            positionExitOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER).withOrderGroup(interval);
            log.info("submiting postion short exit " + positionExitOrder + " for position " + getLongPosition(market, interval));
            try {
                orderService.placeOrder(positionExitOrder);
                for (Fill exitedFill : exitedFills)
                    exitedFill.setPositionType(PositionType.EXITING);
            } catch (Throwable e) {
                log.error(this.getClass().getSimpleName() + ":createShortExit for interval " + interval + " unable to place order " + positionExitOrder
                        + ". Threw a Execption, full stack trace follows:", e);

            }
            return;
        }

    }

    class handleCancelAllShortOpeningSpecificOrders implements Runnable {
        private final Portfolio portfolio;
        private final Market market;

        //  @Inject
        // protected transient OrderService orderService;

        // protected Logger log;

        public handleCancelAllShortOpeningSpecificOrders(Portfolio portfolio, Market market) {
            this.portfolio = portfolio;
            this.market = market;

            // TODO Auto-generated constructor stub

        }

        @Override
        public void run() {
            //if (!orderService.getPendingShortOpenOrders(portfolio).isEmpty())
            orderService.handleCancelAllShortOpeningSpecificOrders(portfolio, market);
            if (positionMap.get(market).getType() == (PositionType.ENTERING) || positionMap.get(market).getType() == (PositionType.EXITING))

                revertPositionMap(market);

        }
    }

    public void buildExitLongOrders(double interval, ExecutionInstruction execInst, Book b, Market market) {

        log.debug(this.getClass().getSimpleName() + ":buildExitLongOrders - Cancelling all long open specific order for interval " + interval);
        orderService.handleCancelAllLongOpeningSpecificOrders(portfolio, market, interval);
        //log.debug(this.getClass().getSimpleName() + ":buildExitLongOrders - Cancelling all long closing specifc taker orders");
        //orderService.handleCancelAllLongClosingSpecificOrders(portfolio, market, ExecutionInstruction.TAKER);
        log.debug(this.getClass().getSimpleName() + ":buildExitLongOrders - Cancelling all long open general orders for interval " + interval);
        orderService.handleCancelAllLongOpeningGeneralOrders(portfolio, market, interval);
        // log.debug(this.getClass().getSimpleName() + ":buildExitLongOrders - Cancelling all long exit stop orders");
        // orderService.handleCancelAllLongStopOrders(portfolio, market);
        //log.debug(this.getClass().getSimpleName() + ":buildExitLongOrders - Cancelling all long working closing orders orders");
        // log.debug(this.getClass().getSimpleName() + ":buildExitLongOrders - Cancelling all working stop orders: "
        //       + orderService.getPendingLongCloseOrders(portfolio, market));
        //orderService.handleCancelAllLongClosingSpecificOrders(portfolio, market);

        log.debug(this.getClass().getSimpleName() + ":buildExitLongOrders - Cancelling all untriggered stop orders for interval " + interval + ": "
                + orderService.getPendingLongStopOrders(portfolio, market, interval));
        orderService.handleCancelAllLongStopOrders(portfolio, market, interval);

        //  .handleCancelAllLongStopOrders(portfolio, market);
        if ((orderService.getPendingLongStopOrders(portfolio, market, interval) == null || (orderService.getPendingLongStopOrders(portfolio, market, interval) != null && orderService
                .getPendingLongStopOrders(portfolio, market, interval).isEmpty()))
                && (orderService.getPendingLongOpenOrders(portfolio, market, interval) == null || (orderService.getPendingLongOpenOrders(portfolio, market,
                        interval) != null && orderService.getPendingLongOpenOrders(portfolio, market, interval).isEmpty()))) {
            Collection<SpecificOrder> longCloseOrders = orderService.getPendingLongCloseOrders(portfolio, market, interval);

            createLongExit(interval, market, longCloseOrders);
        }
    }

    public void buildExitShortOrders(double interval, ExecutionInstruction execInst, Book b, Market market) {

        log.debug(this.getClass().getSimpleName() + ":buildExitShortOrders - For interval " + interval + " cancelling all short open specific orders");
        orderService.handleCancelAllShortOpeningSpecificOrders(portfolio, market);
        log.debug(this.getClass().getSimpleName() + ":buildExitShortOrders - For interval " + interval + " cancelling all short opening general orders");
        orderService.handleCancelAllShortOpeningGeneralOrders(portfolio, market);
        // might be working stop orders that have not yet been filled, so we need to cancel any unfilled triggered orders, as we exiting postion we can cancel all shortexitstopporders

        //  log.debug(this.getClass().getSimpleName() + ":buildExitShortOrders - Cancelling all short exit stop orders");
        // orderService.handleCancelAllShortStopOrders(portfolio, market);
        // log.debug(this.getClass().getSimpleName() + ":buildExitLongOrders - Cancelling all long working closing orders orders");
        //  log.debug(this.getClass().getSimpleName() + ":buildExitShortOrders - Cancelling all working stop orders: "
        //        + orderService.getPendingShortCloseOrders(portfolio, market));

        // orderService.handleCancelAllShortClosingSpecificOrders(portfolio, market);
        log.debug(this.getClass().getSimpleName() + ":buildExitShortOrders - For interval " + interval + " cancelling all untriggered stop orders: "
                + orderService.getPendingShortStopOrders(portfolio, market));

        orderService.handleCancelAllShortStopOrders(portfolio, market);

        // .handleCancelAllShortOpeningGeneralOrders(portfolio, market);

        //orderService.handleCancelAllShortStopOrders(portfolio, market);
        if ((orderService.getPendingShortStopOrders(portfolio, market) == null || (orderService.getPendingShortStopOrders(portfolio, market) != null && orderService
                .getPendingShortStopOrders(portfolio, market).isEmpty()))
                && (orderService.getPendingShortOpenOrders(portfolio, market) == null || (orderService.getPendingShortOpenOrders(portfolio, market) != null && orderService
                        .getPendingShortOpenOrders(portfolio, market).isEmpty()))) {
            Collection<SpecificOrder> shortCloseOrders = orderService.getPendingShortCloseOrders(portfolio, market, interval);

            createShortExit(interval, market, shortCloseOrders);
        }
        // buildOrders();
        //            try {
        //                startSignal.await();
        //                buildOrders();
        //            } catch (InterruptedException e) {
        //                // TODO Auto-generated catch block
        //                e.printStackTrace();
        //            }
        //
        //            endSignal.countDown();
    }

    private void enterLongOrders(double interval, double scaleFactor, double entryPrice, ExecutionInstruction execInst, Market market, Market pairMarket) {
        double targetPrice = entryPrice;

        Collection<Market> markets = new ArrayList<Market>();
        markets.add(market);
        if (pairMarket != null)
            markets.add(pairMarket);

        //Collection<SpecificOrder> longOpenOrders = orderService.handleCancelAllLongOpeningSpecificOrders(portfolio, market);
        Collection<SpecificOrder> longOpenOrders = orderService.getPendingLongOpenOrders(portfolio, market, interval);
        //       .getLongOpeningSpecificOrders(portfolio, market);
        for (Market tradedMarket : markets) {
            orderService.handleCancelAllLongClosingSpecificOrders(portfolio, tradedMarket, interval);
            //    orderService.handleCancelAllLongOpeningGeneralOrders(portfolio, tradedMarket);
            //  orderService.handleCancelAllLongOpeningSpecificOrders(portfolio, tradedMarket);

            if (positionMap.get(tradedMarket) != null
                    && (positionMap.get(tradedMarket).getType() == (PositionType.ENTERING) || positionMap.get(tradedMarket).getType() == (PositionType.EXITING)))

                revertPositionMap(tradedMarket);

            updatePositionMap(tradedMarket, PositionType.ENTERING);

            if (!orderService.getPendingLongCloseOrders(portfolio, market, interval).isEmpty()
                    || !orderService.getPendingLongOpenOrders(portfolio, market, interval).isEmpty()) {
                log.info("Long Entry Prevented for interval " + interval + " as already have working long orders.");

                revertPositionMap(tradedMarket);

                return;
            }
        }

        ArrayList<Order> orderList = buildEnterLongOrders(interval, scaleFactor, entryPrice, execInst, targetPrice, adjustStops, longOpenOrders, market,
                pairMarket);
        log.info("Long High Indicator Processed for interval " + interval);

        if (orderList == null) {
            //  revertPositionMap(market);
            return;
        }

        for (Order order : orderList)

            placeOrder(order);

    }

    Amount getStartingOrignalBal(Market market) {
        //  getMarketAllocations().keySet()
        return startingOrignalBal;

        /*   if (getMarketAllocations() != null && getMarketAllocations().get(market) != null)

               return startingOrignalBal.times(getMarketAllocations().get(market), Remainder.ROUND_EVEN);
           return null;*/
    }

    private long getSlippagePips(DiscreteAmount price) {
        //  return slippage;
        // want to round to nearst pip
        return Math.round(price.getCount() * slippage);
    }

    private double getLossScalingFactor() {
        //  if (previousBal != null)
        //    return (previousBal.dividedBy(baseFixedMaxLoss, Remainder.ROUND_EVEN)).asDouble();
        //else
        return lossScalingFactor;
        //  .divide(baseFixedMaxLoss, Remainder.ROUND_EVEN));
        //  return lossScalingFactor;
    }

    public double getVolatilityTarget() {
        return volatilityTarget;
    }

    static Amount getOriginalBaseNotionalBalance(Market market) {
        if (getMarketAllocations() != null && getMarketAllocations().get(market) != null && originalBaseNotionalBalance != null)
            return originalBaseNotionalBalance.times(getMarketAllocations().get(market), Remainder.ROUND_EVEN);
        return null;
    }

    public synchronized ArrayList<Order> buildEnterOrders(double interval, double scaleFactor, double forecast, ExecutionInstruction execInst,
            double targetPrice, boolean adjustStops, Collection<SpecificOrder> openLongOrders, Collection<SpecificOrder> openShortOrders, Market market) {
        // TODO Auto-generated method stub
        Collection<SpecificOrder> openOrders = (Collection<SpecificOrder>) ((openLongOrders != null && openShortOrders != null) ? openLongOrders
                .addAll(openShortOrders) : (openLongOrders != null) ? openLongOrders : openShortOrders);
        ArrayList<Order> orderList = new ArrayList<Order>();

        Amount maxPositionUnits;
        Asset tradedCCY = (market.getTradedCurrency(market) == null) ? market.getQuote() : market.getTradedCurrency(market);
        Listing listing = Listing.forPair(tradedCCY, portfolio.getBaseAsset());
        Listing tradedListing = Listing.forPair(market.getBase(), portfolio.getBaseAsset());
        Offer rate = quotes.getImpliedBestAskForListing(listing);
        Offer baseRate = quotes.getImpliedBestAskForListing(tradedListing);
        Offer bestAsk = (execInst == (ExecutionInstruction.TAKER)) ? quotes.getLastAskForMarket(market) : quotes.getLastBidForMarket(market);
        double volatility = getVol(market, interval);
        double priceVolatility = getPriceVol(market, getVolatilityInterval());
        double pricePointsVolatility = getPricePointsVol(market, getVolatilityInterval());
        double impliedShortPricePointsVolatility = (getPricePointsVol(market, trendInterval * 2)) * (Math.sqrt(86400 / (trendInterval * 2)));
        double impliedMedPricePointsVolatility = (getPricePointsVol(market, trendInterval * 8)) * (Math.sqrt(86400 / (trendInterval * 8)));
        double impliedLongPricePointsVolatility = (getPricePointsVol(market, trendInterval * 16)) * (Math.sqrt(86400 / (trendInterval * 16)));

        if (pricePointsVolatility == 0) {
            log.info("Order Entry Prevented for interval " + interval + " as no price no pricePointsVolatility at: " + context.getTime());
            revertPositionMap(market);
            return null;
        }
        if (forecast < 0)
            log.debug("wait");
        // double atr = (bestAsk.getPriceCountAsDouble() - getShortLow(market, interval));
        /// volatility) * 100;
        //   double atr = atrStop * pricePointsVolatility * 20;
        double atr = pricePointsVolatility * atrStop * 20;

        double avg = getAvg(market);
        //double vol = getVol(market);
        log.debug("Order Entry Trigger for interval " + interval + " with atr: " + atr + " atrStop " + atrStop + " average " + avg + " and volatility "
                + volatility + " priceVolatility " + priceVolatility + " scaleFactor " + scaleFactor);
        Amount positionUnits = DecimalAmount.ONE;
        Amount positionVolume = DecimalAmount.ZERO;
        Amount workingLongVolume = DecimalAmount.ZERO;
        if (previousBal == null || previousBal.isZero()) {
            previousBal = getBaseLiquidatingValue();
            previousRestBal = previousBal;
        }
        if (getStartingOrignalBal(market) == null && previousBal != null && !previousBal.isZero())
            startingOrignalBal = previousRestBal;
        if ((bestAsk == null || bestAsk.getPriceCount() == 0)) {
            log.info("Long Entry Prevented for interval " + interval + " as no bid at: " + context.getTime());
            revertPositionMap(market);
            return null;
        }
        DiscreteAmount atrDiscrete = (new DiscreteAmount((long) (atr), market.getPriceBasis()));
        double contractSize = (market.getContractSize(market));
        DiscreteAmount limitPrice = bestAsk.getPrice().increment(getSlippagePips(bestAsk.getPrice()));
        Amount dollarsPerPoint = (limitPrice.increment().minus(limitPrice))
                .times(market.getMultiplier(market, limitPrice, limitPrice.increment()), Remainder.ROUND_EVEN).times(contractSize, Remainder.ROUND_EVEN)
                .times(1 / limitPrice.getBasis(), Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);
        if (dollarsPerPoint == null || dollarsPerPoint.isZero()) {
            log.info("Order Entry Prevented for interval " + interval + " as dollarsPerPoint is null at: " + context.getTime());
            revertPositionMap(market);

            return null;

        }
        Amount blockValue = dollarsPerPoint.times(0.01, Remainder.ROUND_EVEN).times(limitPrice, Remainder.ROUND_EVEN);
        log.info(this.getClass().getSimpleName() + ":buildEnterOrders - Block value: " + blockValue + " wiht limit price " + limitPrice
                + " and dollarsPerPoint " + dollarsPerPoint);

        DiscreteAmount stopDiscrete = new DiscreteAmount((long) (atr), market.getPriceBasis());
        if (stopDiscrete.isZero()) {
            log.info("Order Entry Prevented for interval " + interval + " as ATR is zero at: " + context.getTime());
            revertPositionMap(market);
            return null;
        }

        if (rawSignals && orderService instanceof MockOrderService) {

            if ((getNetPosition(market, interval) != null && getNetPosition(market, interval).getLongVolume() != null) || !openOrders.isEmpty()) {
                Amount openPostionVolume = getNetPosition(market, interval).getLongVolume();

                //  positionVolume = getNetPosition(market).getLongVolume();
                positionVolume = getNetPosition(market, interval).getOpenVolume();
                log.debug("Long Entry for interval " + interval + " current position volume:" + positionVolume);

                //    if (positionVolume.isNegative())
                //      log.error("long postion break");
                if (openPostionVolume != null && positionVolume.compareTo(openPostionVolume) != 0) {
                    // net postion uses all fills, long volume is open && long + close && short || postion Effect ==null
                    // long only uses fills on long side.
                    // 2016-09-25 08:20:53 [pool-14-thread-2] INFO  org.cryptocoinpartners.portfolio - long postion break Net Position:Id=dc9c6e72-0f7e-4119-93fb-a1115a46b44c,Exchange=OKCOIN_THISWEEK,, Short Qty=0,, Short Avg Price=777.7700000000000000000000000000000000,, Short Avg Stop Price=0E-34,Long Qty=-4,Long Avg Price=0,Long Avg Stop Price=0, Net Qty=-4 Vol Count=-4,  Entry Date=, Instrument=BTC Long postion:Id=394b8fc6-c139-4164-9028-6d8747292508,Exchange=null,, Short Qty=0,, Short Avg Price=0,, Short Avg Stop Price=0,Long Qty=0,Long Avg Price=0,Long Avg Stop Price=0, Net Qty=0 Vol Count=0,  Entry Date=, Instrument=null

                    log.info("long postion break Net Position:" + getNetPosition(market, interval) + " Long postion:" + getLongPosition(market, interval));
                    log.info("net position fills:" + getNetPosition(market, interval).getFills());
                    log.info("long position fills :" + getLongPosition(market, interval).getFills());
                    // log.info("long postion break");
                    positionVolume = getNetPosition(market, interval).getOpenVolume();
                    openPostionVolume = getNetPosition(market, interval).getOpenVolume();
                }

            } else {
                log.info("Long Entry Prevented for interval " + interval + " as already long " + getNetPosition(market, interval) + "with working orders "
                        + openOrders + " at: " + context.getTime());
                revertPositionMap(market);

                return null;
            }

            DiscreteAmount orderDiscrete = new DiscreteAmount(Long.parseLong("1"), market.getVolumeBasis());

            // DiscreteAmount.(BigDecimal.ONE, market.getVolumeBasis());
            DecimalAmount maxAssetPosition = DecimalAmount.of(BigDecimal.ONE);

            log.debug("long entry for interval " + interval + " order order discrete: " + BigDecimal.ONE.negate() + " position volume: " + positionVolume
                    + " max asset position : " + maxAssetPosition);
            orderDiscrete = (orderDiscrete.compareTo(maxAssetPosition.minus(getLongPosition(market, interval).getOpenVolume().abs())) >= 0) ? (maxAssetPosition
                    .minus(getLongPosition(market, interval).getOpenVolume().abs())).toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR) : orderDiscrete;

            if (orderDiscrete.isZero() || orderDiscrete.isNegative()) {
                revertPositionMap(market);

                return null;
            }

            log.debug(this.getClass().getSimpleName() + ":BuildEnterLongOrders - trading with raw singals and fixed order size of one");

            GeneralOrder longOrder = generalOrderFactory
                    .create(context.getTime(), portfolio, market, orderDiscrete.asBigDecimal(), FillType.TRAILING_STOP_LOSS);
            longOrder.withComment(interval + " Long Entry Order").withStopAmount(stopDiscrete.asBigDecimal()).withTimeToLive(timeToLive)
            // .withPositionEffect(PositionEffect.OPEN);
                    .withPositionEffect(PositionEffect.OPEN).withExecutionInstruction(execInst);
            longOrder.withOrderGroup(interval);
            longOrder.withLimitPrice(limitPrice.asBigDecimal());
            // longOrder.withLimitPrice(null);
            orderList.add(longOrder);
            return orderList;

        }

        Amount cashBal = portfolioService.getAvailableBaseBalance(tradedCCY, market.getExchange());
        Amount totalBal = cashBal;

        DiscreteAmount targetDiscrete;

        if (targetPrice == 0) {
            targetDiscrete = new DiscreteAmount((long) (atrTarget * atr), market.getPriceBasis());
        } else {

            targetDiscrete = (forecast > 0) ? (DiscreteAmount) (new DiscreteAmount((long) (targetPrice), market.getPriceBasis())).minus(limitPrice)
                    : (DiscreteAmount) (limitPrice.minus(new DiscreteAmount((long) (targetPrice), market.getPriceBasis()))).abs();

            //   asdfasdf     

            //       limitPrice.minus(new DiscreteAmount((long) (targetPrice), market.getPriceBasis())));
            // stopDiscrete = new DiscreteAmount((long) (targetDiscrete.getCount() * atrStop), bestAsk.getMarket().getPriceBasis());

        }
        // if (targetDiscrete.isNegative())
        //   return null;
        Amount atrUSDDiscrete = atrDiscrete.times(dollarsPerPoint, Remainder.ROUND_CEILING);

        Amount PercentProfit = DecimalAmount.ZERO;
        Amount notionalBaseBalance = getOriginalBaseNotionalBalance(market);

        if (riskManageNotionalBalance) {
            Amount currentBal = getBaseLiquidatingValue().times(getMarketAllocations().get(market), Remainder.ROUND_EVEN);
            Amount scaledBal = (currentBal.compareTo(previousBal) < 0) ? previousBal.minus((previousBal.minus(getBaseLiquidatingValue())).times(
                    getLossScalingFactor(), Remainder.ROUND_EVEN)) : currentBal;
            notionalBaseBalance = notionalBaseBalance.times((scaledBal.dividedBy(currentBal, Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN);
            if (previousBal == null)
                previousBal = currentBal;
            else
                previousBal = (currentBal.compareTo(previousBal) > 0) ? currentBal : previousBal;
            //  Amount PercentProfit = DecimalAmount.ONE.minus(getBaseCashBalance().times(startingOrignalBal.invert(), Remainder.ROUND_EVEN));
        } else {
            PercentProfit = DecimalAmount.ONE.minus(getBaseLiquidatingValue().times(previousBal.invert(), Remainder.ROUND_EVEN));

            //PercentProfit = DecimalAmount.ONE.minus(getBaseCashBalance().times(getStartingOrignalBal(market).invert(), Remainder.ROUND_EVEN));
            //Does not consider loss of cureent working orders!
            if (!DecimalAmount.ONE.minus(PercentProfit.times(getLossScalingFactor(), Remainder.ROUND_EVEN)).isPositive())
                return null;
            notionalBaseBalance = (PercentProfit.isPositive()) ? notionalBaseBalance.times(
                    DecimalAmount.ONE.minus(PercentProfit.times(getLossScalingFactor(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN) : notionalBaseBalance;
        }
        //TODO persitance here
        //       if (portfolio.getBaseNotionalBalance().times(getMarketAllocations().get(market), Remainder.ROUND_EVEN).compareTo(notionalBaseBalance) != 0) {
        //         portfolio.setBaseNotionalBalanceCount(notionalBaseBalance.toBasis(portfolio.getBaseAsset().getBasis(), Remainder.ROUND_EVEN).getCount());
        //       portfolio.merge();
        //  }
        if (getStartingBaseNotionalBalance(market) == null)
            setStartingBaseNotionalBalance(notionalBaseBalance);

        if (!notionalBaseBalance.isPositive()) {

        }
        //reinvet profit
        // originalNotionalBalanceUSD.times(DecimalAmount.ONE.minus(PercentProfit), Remainder.ROUND_EVEN);
        //    notionalBalanceUSD = notionalBalanceUSD.times(limitPrice.divide(basePrice.asBigDecimal(), Remainder.ROUND_EVEN), Remainder.ROUND_EVEN);
        notionalBaseBalance = notionalBaseBalance.times(scaleFactor, Remainder.ROUND_EVEN);
        log.debug(this.getClass().getSimpleName() + ":BuildEnterLongOrders for interval " + interval + " at " + context.getTime() + " - notionalBalanceUSD: "
                + portfolio.getBaseNotionalBalance() + " notionalBaseBalance " + notionalBaseBalance + " Cash Balance: " + getBaseLiquidatingValue()
                + " startingOrignalBal: " + getStartingOrignalBal(market) + " originalNotionalBalanceUSD: " + getOriginalBaseNotionalBalance(market)
                + " lossScalingFactor: " + getLossScalingFactor() + " PercentProfit: " + PercentProfit + " scalingFactor " + scaleFactor);

        notionalBalance = notionalBaseBalance.times(rate.getPrice().invert(), Remainder.ROUND_EVEN);
        log.debug(this.getClass().getSimpleName() + ":BuildEnterLongOrders for interval " + interval + " - notionalBalance: " + notionalBalance
                + " notionalBalanceUSD: " + notionalBaseBalance + " tradedRate: " + baseRate.getPrice());

        //TODO Building postions too quickly  to max position size, so when they go wrong we make a big loss. need to ignore single is prvious trade wasa winnder
        if (!notionalBaseBalance.isPositive() || atrUSDDiscrete.isZero() || stopDiscrete.isZero()) {
            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            log.info("Order Entry Prevented for interval " + interval + " at " + context.getTime() + " as atrUSDDiscrete: " + atrUSDDiscrete
                    + " notionalBaseBalance:" + notionalBaseBalance);
            revertPositionMap(market);
            //(OTEBal, bestAsk);
            //  return orderList;
            return null;
        }

        Amount maxAssetPosition = ((notionalBaseBalance.times(getVolatilityTarget(), Remainder.ROUND_FLOOR)).dividedBy(Math.sqrt(365.0), Remainder.ROUND_FLOOR))
                .dividedBy(blockValue.times(priceVolatility, Remainder.ROUND_FLOOR), Remainder.ROUND_FLOOR);

        //   log.info(this.getClass().getSimpleName() + ":buildEnterOrders - Block value: " + blockValue + " wiht limit price " + limitPrice + " and dollarsPerPoint "+ dollarsPerPoint);

        Amount unitSize = (maxAssetPosition.times(forecast, Remainder.ROUND_FLOOR)).dividedBy(10.0, Remainder.ROUND_FLOOR);
        //(maxAssetPosition.times(forecast, Remainder.ROUND_FLOOR)).dividedBy(10, Remainder.ROUND_FLOOR);
        log.info(this.getClass().getSimpleName() + ":buildEnterOrders - Target Position: " + unitSize + " with forecast " + forecast + ", notionalBaseBalance "
                + notionalBaseBalance + ", priceVolatility " + priceVolatility + "and blockValue " + blockValue);

        log.debug("Order Entry unit size for interval " + interval + " :" + unitSize + "atr usd discrete: " + atrUSDDiscrete + " dollars per point: "
                + dollarsPerPoint + " national USD balance:" + notionalBaseBalance);

        if (unitSize.isZero()) {
            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            // updateStops(OTEBal, bestAsk);
            log.info("Long Entry Prevented for interval " + interval + " as units size is zero at: " + context.getTime());
            revertPositionMap(market);
            return null;
        }
        if (getNetPosition(market, interval) != null && getNetPosition(market, interval).getOpenVolume() != null) {
            Amount openPostionVolume = getNetPosition(market, interval).getOpenVolume();
            positionVolume = getNetPosition(market, interval).getOpenVolume();
            log.debug("Order Entry for interval " + interval + " current position volume:" + positionVolume);

            // //    if (positionVolume.isNegative())
            //      log.info("long postion break");
            //    if (openPostionVolume != null && positionVolume.compareTo(openPostionVolume) != 0) {

            //    log.info("long postion break Net Position:" + getNetPosition(market, interval) + " Long postion:" + getLongPosition(market, interval));

            //  // log.info("long postion break");
            //positionVolume = getNetPosition(market, interval).getLongVolume();
            //openPostionVolume = getLongPosition(market, interval).getOpenVolume();
            // }
            positionUnits = positionVolume.dividedBy(unitSize, Remainder.ROUND_EVEN);
        }
        Amount exitPrice = limitPrice.minus(stopDiscrete);
        log.debug("Order entry for interval " + interval + " order exit price: " + exitPrice + " long entry order limit price: " + limitPrice);

        Amount priceDiff;
        Amount positionPriceDiff;
        Amount openOrderPriceDiff;

        priceDiff = (exitPrice.compareTo(limitPrice) < 0) ? (limitPrice).minus(exitPrice) : (exitPrice).minus(limitPrice);
        positionPriceDiff = (getNetPosition(market, interval).getLongAvgStopPrice().compareTo(getNetPosition(market, interval).getLongAvgPrice()) < 0) ? (getNetPosition(
                market, interval).getLongAvgPrice()).minus(getNetPosition(market, interval).getLongAvgStopPrice()) : (getNetPosition(market, interval)
                .getLongAvgStopPrice()).minus(getNetPosition(market, interval).getLongAvgPrice());

        openOrderPriceDiff = (Order.getOpenAvgStopPrice(openOrders).compareTo(Order.getOpenAvgPrice(openOrders)) < 0) ? (Order.getOpenAvgPrice(openOrders))
                .minus(Order.getOpenAvgStopPrice(openOrders)) : (Order.getOpenAvgStopPrice(openOrders)).minus(Order.getOpenAvgPrice(openOrders));

        //Max  loss in traded currency current position
        //Amount of loss in quote currency

        //BTC I will loss on exisit postions
        Amount lossAmountPerShare = priceDiff;

        if (getNetPosition(market, interval) != null && getNetPosition(market, interval).getLongVolume() != null
                && getNetPosition(market, interval).getLongVolume().isPositive())
            log.debug("checkpoint");
        //  Amount currentMarketLossAmount = (limitPrice.minus(stopDiscrete).times(market.getContractSize(), Remainder.ROUND_EVEN)).times(
        //        limitPrice.minus(stopDiscrete), Remainder.ROUND_EVEN);
        Amount currentMaxLossAmount;
        //     ETH/BTC (traded BTC)
        //   BTC/USD
        Amount weightedExitPrice = DecimalAmount.ONE;
        Amount weightedEntryPrice = DecimalAmount.ONE;
        //   BTC/USD (BTC is traded), ETH/BTC (ETH is traded)
        // if (!(market.getTradedCurrency(market).equals(market.getQuote())))

        //TODO check theis the weight price when to coauclauted and applydoes not seem right.
        weightedExitPrice = //(market.getContractSize(market) == 1 ? DecimalAmount.ONE : 
        (Order.getOpenVolume(openOrders, market).plus(positionVolume)).isZero() ? exitPrice : (((getNetPosition(market, interval).getLongAvgStopPrice().times(
                positionVolume, Remainder.ROUND_EVEN)).plus(Order.getOpenAvgStopPrice(openOrders).times(Order.getOpenVolume(openOrders, market),
                Remainder.ROUND_EVEN))).dividedBy((Order.getOpenVolume(openOrders, market).plus(positionVolume)), Remainder.ROUND_EVEN));
        //);
        weightedExitPrice = weightedExitPrice.times(1 - slippage, Remainder.ROUND_FLOOR);
        weightedEntryPrice =
        //market.getContractSize(market) == 1 ? DecimalAmount.ONE : 
        (Order.getOpenVolume(openOrders, market).plus(positionVolume)).isZero() ? limitPrice : (((getNetPosition(market, interval).getLongAvgPrice().times(
                positionVolume, Remainder.ROUND_EVEN)).plus(Order.getOpenAvgPrice(openOrders).times(Order.getOpenVolume(openOrders, market),
                Remainder.ROUND_EVEN))).dividedBy((Order.getOpenVolume(openOrders, market).plus(positionVolume)), Remainder.ROUND_EVEN));
        // loss in quote currenent if no traded ccy is left
        Amount currentMaxLoss = (weightedExitPrice.minus(weightedEntryPrice)).abs().times(
                (getLongPosition(market, interval).getOpenVolume().plus(Order.getOpenVolume(openOrders, market))), Remainder.ROUND_EVEN);
        if (lossAmountPerShare.isZero()) {
            log.info("BulidEnterLongOrders: For interval " + interval + " exiting as Loss Amount is Zero." + " priceDiff: " + priceDiff + "multiplier: "
                    + market.getMultiplier(market, limitPrice, exitPrice) + " contractSize: " + market.getContractSize(market) + " currentMaxLoss: "
                    + currentMaxLoss + " exitPrice: " + exitPrice);
            revertPositionMap(market);

            return null;
        }
        //, remainderHandler)))
        // if (market.getTradedCurrency(market).equals(market.getQuote()))
        //   currenMaxLoos is the price diff x the volume, so we need to times it by mutlplier

        Amount baseCurrentMaxLossAmount = currentMaxLoss.times(market.getMultiplier(market, weightedEntryPrice, weightedExitPrice), Remainder.ROUND_EVEN)
                .times(contractSize, Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);

        //                .times(weightedExitPrice, Remainder.ROUND_CEILING)).times(weightedExitPrice, Remainder.ROUND_EVEN);

        currentMaxLossAmount = (notionalBaseBalance.times(maxLossTarget, Remainder.ROUND_EVEN)).minus(baseCurrentMaxLossAmount);
        //  else
        //    currentMaxLossAmount = (notionalBaseBalance.times(maxLossTarget, Remainder.ROUND_EVEN)).minus(currentMaxLoss.times(weightedExitPrice,
        //          Remainder.ROUND_CEILING));
        //TODO need to add a loop over the cancel orders are re-submit at new limit price to ensure any previously triggered units are added back to market and filled if they meet the volume allowence.

        //    Amount dollarsPerPoint = (limitPrice.increment().minus(limitPrice))
        //          .times(market.getMultiplier(market, limitPrice, limitPrice.increment()), Remainder.ROUND_EVEN).times(contractSize, Remainder.ROUND_EVEN)
        //        .times(1 / limitPrice.getBasis(), Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);
        //for cash
        //price diff X rate
        // for dervies
        // price diff x multipliter * contract size * rate

        Amount MaxUnits = ((currentMaxLossAmount).dividedBy(
                ((lossAmountPerShare.times(dollarsPerPoint, Remainder.ROUND_CEILING)).times(unitSize, Remainder.ROUND_FLOOR)), Remainder.ROUND_FLOOR)).toBasis(
                1, Remainder.ROUND_FLOOR);

        //  DiscreteAmount maxAssetPosition = (MaxUnits.times(unitSize, Remainder.ROUND_FLOOR)).toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR);
        //.plus(getLongPosition(market).getOpenVolume().plus(Order.getOpenVolume(longOpenOrders, market)));

        // (maxAssetPosition.toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR));

        // (lossBalance).dividedBy(((priceDiff).times(market.getContractSize(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN);
        log.debug("Long Entry for interval " + interval + " Max Asset Position: " + maxAssetPosition + "notional balance " + notionalBalance
                + " notional base balance: " + notionalBaseBalance + " maxLossTarget : " + maxLossTarget + " lossAmount" + lossAmountPerShare
                + "baseCurrentMaxLossAmount: " + baseCurrentMaxLossAmount + "currentMaxLossAmount: " + currentMaxLossAmount + " price diff: " + priceDiff
                + " multiplier: " + market.getMultiplier(market, limitPrice, exitPrice) + " contract size: " + market.getContractSize(market) + "max units:"
                + MaxUnits);

        // so I am negative

        //lets close out existing positon if we need to reduce current position.
        //
        //if they hte forecast and position are of differn signes, then close out, if the unit size < current position then we need to close out.
        DiscreteAmount unitSizeDiscerte = unitSize.toBasis(market.getVolumeBasis(), Remainder.ROUND_CEILING);

        DiscreteAmount positionVolumeDiscerete = (getNetPosition(market, interval).getOpenVolume().plus(Order.getOpenVolume(openOrders, market))).toBasis(
                market.getVolumeBasis(), Remainder.ROUND_CEILING);
        double numerator = Math.max(unitSizeDiscerte.getCount(), positionVolumeDiscerete.getCount());
        double denumenator = Math.min(unitSizeDiscerte.getCount(), positionVolumeDiscerete.getCount());
        double delta = Math.abs((numerator / denumenator) - 1);
        if (!positionVolumeDiscerete.isZero() && delta < positionInertia)
            return null;
        //  unitSize
        //Math.max(, b)
        //if (positionVolume)
        log.info(this.getClass().getSimpleName() + "buildEnterOrders - Position Volume " + positionVolume + " wokring order volume "
                + Order.getOpenVolume(openOrders, market) + "net postion open volume " + getNetPosition(market, interval).getOpenVolume());

        if (!positionVolume.isZero()
                && (positionVolume.times(unitSize, Remainder.ROUND_EVEN).isNegative() || unitSize.abs().minus(positionVolume.abs()).isNegative())) {

            //   if (!maxAssetPosition.minus(positionVolume).isPositive()) {
            //  revertPositionMap(market);
            log.info("Order Entry Prevented for interval " + interval + " as  positionVolume :" + positionVolume + " is greater than maxAssetPosition: "
                    + maxAssetPosition);
            //  if (maxAssetPosition.minus(positionVolume.abs()).isNegative()) {
            //so we are short and we have too many lots, so we need to buy some.
            // the amount we need to buy is 
            //  String comment = (positionVolume.isNegative() ? " Short Exit Order" : " Long Exit Order");

            Amount closeOutVolume = (positionVolume.times(unitSize, Remainder.ROUND_EVEN).isNegative()) ? positionVolume.negate() : unitSize
                    .minus(positionVolume);
            String comment = (closeOutVolume.isPositive() ? interval + " Short Exit Order  " + forecast + " " + unitSize + "/" + positionVolume : interval
                    + " Long Exit Order " + forecast + " " + unitSize + "/" + positionVolume);

            SpecificOrder positionRebalOrder = specificOrderFactory.create(context.getTime(), portfolio, market, closeOutVolume, comment);

            positionRebalOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER).withOrderGroup(interval);

            orderList.add(positionRebalOrder);

            // return orderList;

            //              log.info("Long Entry Prevented for interval " + interval + " as  positionVolume :" + positionVolume + " is greater than maxAssetPosition: "
            //                    + maxAssetPosition);

            //  }
            // return null;
        }

        //  unitSize = unitSize.toBasis(market.getVolumeBasis(), Remainder.ROUND_CEILING);
        Amount orderDiscrete = (positionVolume.times(unitSizeDiscerte, Remainder.ROUND_EVEN).isPositive() && !positionVolume.abs()
                .minus(unitSizeDiscerte.abs()).isNegative()) ? DecimalAmount.ZERO
                : (positionVolume.times(unitSizeDiscerte, Remainder.ROUND_EVEN).isPositive() && unitSizeDiscerte.abs().minus(positionVolume.abs()).isPositive()) ? unitSizeDiscerte
                        .minus(positionVolume) : unitSizeDiscerte;

        //    (forecast < 0) ? (unitSize.negate().minus(positionVolume)) : (unitSize.minus(positionVolume));

        log.debug("Order entry for interval " + interval + " order order discrete: " + orderDiscrete + " position volume: " + positionVolume
                + " max asset position: " + maxAssetPosition + "unit size" + unitSize);
        /*        if (maxAssetPosition.compareTo((getLongPosition(market).getOpenVolume().plus(Order.getOpenVolume(longOpenOrders, market)))) < 0) {

                    log.info("long entry order order prevneted as current long position volume: " + positionVolume + " and open order volume: "
                            + Order.getOpenVolume(longOpenOrders, market) + " is greater than max position of " + maxAssetPosition);
                    return null;
                }*/
        //orderDiscrete = (DiscreteAmount) ((orderDiscrete.compareTo((maxAssetPosition).minus((getLongPosition(market).getOpenVolume().plus(Order.getOpenVolume(
        //  longOpenOrders, market))))) >= 0) ? (maxAssetPosition).minus(getLongPosition(market).getOpenVolume().plus(
        // Order.getOpenVolume(longOpenOrders, market))) : orderDiscrete);
        //orderDiscrete.roundedCountForBasis(amount, basis)
        //orderDiscrete.toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR);
        log.debug("long entry for interval " + interval + " order order discrete: " + orderDiscrete + " pongPosition: "
                + getLongPosition(market, interval).getOpenVolume() + "open volume" + Order.getOpenVolume(openOrders, market) + " max asset position: "
                + maxAssetPosition);

        //DiscreteAmount orderDiscrete = maxAssetPosition.toBasis(bestBid.getMarket().getVolumeBasis(), Remainder.ROUND_EVEN);

        //      logger.info("max position:" + maxAssetPosition.toString() + " max loss:" + maxLossTarget + " notila balance: " + notionalBalance.toString());

        if (orderDiscrete.isZero()) {
            log.info("Order Entry for interval " + interval + " Prevented as currnet position: " + positionVolume + " is greater than max position: "
                    + maxAssetPosition + " at:" + context.getTime() + "with atr:" + atrDiscrete);
            log.trace(getNetPosition(market, interval).getFills().toString());

            revertPositionMap(market);
            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            //   updateStops(OTEBal, bestAsk);
            return orderList;
        }
        maxPositionUnits = maxAssetPosition.dividedBy(orderDiscrete, Remainder.ROUND_EVEN);

        //  if (orderDiscrete.isPositive() && (totalPosition.compareTo(maxPositionUnits) <= 0)) {
        //  if (orderDiscrete.isPositive()) {
        GeneralOrder longOrder = generalOrderFactory.create(context.getTime(), portfolio, market, orderDiscrete.asBigDecimal(), FillType.TRAILING_STOP_LOSS);

        String comment = (longOrder.isBid() ? " Long Entry Order " + forecast + " " + unitSize + "/" + positionVolume : " Short Entry Order " + forecast + " "
                + unitSize + "/" + positionVolume);

        longOrder.withComment(interval + comment).withStopAmount(stopDiscrete.asBigDecimal()).withTimeToLive(timeToLive)
                // .withPositionEffect(PositionEffect.OPEN);
                .withTargetAmount(targetDiscrete.asBigDecimal()).withPositionEffect(PositionEffect.OPEN).withExecutionInstruction(execInst)
                .withOrderGroup(interval);
        Collection<SpecificOrder> pendingOrders = (longOrder.isBid()) ? orderService.getPendingLongOrders() : orderService.getPendingShortOrders();
        Amount workingVolume = orderDiscrete;
        for (SpecificOrder workingOrder : pendingOrders)
            workingVolume = workingVolume.plus(workingOrder.getUnfilledVolume());
        // if I am buying, then I can buy at current best ask and sell at current best bid
        Book lastBook = quotes.getLastBook(market);
        log.info(this.getClass().getSimpleName() + ":getEnterLongOrders - For interval " + interval + " setting limit prices for market " + market
                + " using lastBook" + lastBook);
        if (market.getSymbol().equals("OKCOIN_THISWEEK:LTC.USD.THISWEEK"))
            log.debug("test");

        Offer bestOffer = (longOrder.isBid()) ? lastBook.getBestAskByVolume(new DiscreteAmount(DiscreteAmount.roundedCountForBasis(
                workingVolume.asBigDecimal(), market.getVolumeBasis()), market.getVolumeBasis())) : lastBook.getBestBidByVolume(new DiscreteAmount(
                DiscreteAmount.roundedCountForBasis(workingVolume.asBigDecimal(), market.getVolumeBasis()), market.getVolumeBasis()));

        // this is short exit, so I am buy, so hitting the ask
        // loop down asks until the total quanity of the order is reached.
        // buying at ask!
        if (longOrder.getExecutionInstruction() != null && longOrder.getExecutionInstruction().equals(ExecutionInstruction.TAKER) && bestOffer != null
                && bestOffer.getPrice() != null && bestOffer.getPrice().getCount() != 0) {
            // limitPrice = bestOffer.getPrice().increment(getSlippagePips(limitPrice));
            if (longOrder.isBid())
                longOrder.withLimitPrice(limitPrice.increment(getSlippagePips(limitPrice)).asBigDecimal());
            else
                longOrder.withLimitPrice(limitPrice.decrement(getSlippagePips(limitPrice)).asBigDecimal());
            //    longOrder.setFillType(FillType.MARKET);

            log.info(this.getClass().getSimpleName() + ":getEnterLongOrders - For interval " + interval + " setting limit price to best offer by volume"
                    + limitPrice + " for order " + longOrder);
        }
        // specificOrder.withLimitPrice(limitPrice);

        //I want to buy. so will buy at highest price

        //     longOrder.withLimitPrice(limitPrice.increment(slippage).asBigDecimal());
        //  if (execInst == ExecutionInstruction.MAKER)
        //    longOrder.withMarketPrice(limitPrice.increment(getSlippagePips(limitPrice)).asBigDecimal());
        //else
        longOrder.withLimitPrice(limitPrice);
        if (limit) {
            DiscreteAmount triggerPrice = (DiscreteAmount) (new DiscreteAmount((long) (atrTrigger * atr), market.getPriceBasis())).plus(bestAsk.getPrice());

            //DiscreteAmount triggerPrice = bestAsk.getPrice().increment(triggerBuffer);

            longOrder.withTargetPrice(triggerPrice.asBigDecimal());
        }

        // testOrder.persit();
        // GeneralOrderBuilder orderBuilder = order.create(context.getTime(), market, orderDiscrete.asBigDecimal(), FillType.STOP_LOSS)
        //       .withComment("Long Entry Order").withLimitPrice(limitPrice.asBigDecimal()).withStopAmount(stopDiscrete.asBigDecimal())
        // .withPositionEffect(PositionEffect.OPEN);
        //     .withTargetAmount(targetDiscrete.asBigDecimal()).withPositionEffect(PositionEffect.OPEN).withExecutionInstruction(execInst);
        // BUYINg so costs will be in ETHERS, which is the BASE CCY or the traded CCY, need to conver to the traed CCY using the BASE to TRADED CCY conversion
        // ETH/BTC
        // when i am buying, I am buying buying the base I am buying 100 ETH (base). the fees will be in the base CCY.

        Listing costListing = Listing.forPair(market.getBase(), tradedCCY);

        Offer costRate = quotes.getImpliedBestAskForListing(costListing);

        //  asdfasdf
        Amount totalCost = (FeesUtil.getMargin(longOrder).plus(FeesUtil.getCommission(longOrder))).negate().times(costRate.getPrice(), Remainder.ROUND_EVEN);
        // whyis total cost in the base CCY, when we are buying ethers with BTC, charge will be in eithers, i.e either the traded curreny or the 
        // whne I am selling ETH for BTC, the chagge will be in  BTC the traded currency or the quote CCY.
        //BTC balance
        cashBal = portfolioService.getAvailableBalance(tradedCCY, market.getExchange());
        if ((totalCost.compareTo(cashBal) > 0)) {
            Amount totalCostAmmount = (cashBal.isNegative()) ? totalCost.minus(cashBal) : totalCost;
            // get USD balance in BTC.
            cashBal = (portfolioService.getAvailableBalance(portfolio.getBaseAsset(), market.getExchange())).dividedBy(rate.getPrice(), Remainder.ROUND_EVEN);
            if ((totalCost.compareTo(cashBal) > 0)) {
                // Let's adjust quanity
                Amount qtyScale = cashBal.dividedBy((totalCost.times(1.1, Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN);
                //  longOrder.getVolume().times(qtyScale, Remainder.ROUND_EVEN);
                Amount vol = (qtyScale.isZero()) ? longOrder.getVolume() : longOrder.getVolume().times(qtyScale, Remainder.ROUND_EVEN);
                longOrder.setVolumeDecimal(vol.asBigDecimal());
                totalCost = (FeesUtil.getMargin(longOrder).plus(FeesUtil.getCommission(longOrder))).negate();
                if ((totalCost.compareTo(cashBal) > 0)) {

                    log.info("Long Entry Prevented for interval " + interval + "  as total cost: " + totalCost + " is greater than cash balace: " + cashBal
                            + "at " + context.getTime());
                    revertPositionMap(market);

                    // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
                    //updateStops(OTEBal, bestAsk);
                    return null;
                }
            } else if (cashBal.isPositive() && cashBal.compareTo(totalCost) > 0) {

                //neeed to transfer the total cost
                Transaction initialCredit = transactionFactory.create(portfolio, market.getExchange(), tradedCCY, TransactionType.CREDIT, totalCostAmmount,
                        new DiscreteAmount(0, 0.01));
                context.setPublishTime(initialCredit);

                initialCredit.persit();

                context.route(initialCredit);

                Transaction initialDebit = transactionFactory.create(portfolio, market.getExchange(), portfolio.getBaseAsset(), TransactionType.DEBIT,
                        (totalCostAmmount.times(rate.getPrice(), Remainder.ROUND_EVEN)).negate(), new DiscreteAmount(0, 0.01));
                context.setPublishTime(initialDebit);

                initialDebit.persit();

                context.route(initialDebit);

                cashBal = portfolioService.getAvailableBalance(tradedCCY, market.getExchange()).plus(totalCostAmmount);

            }
        }

        if ((totalCost.compareTo(cashBal) <= 0)) {

            //DecimalAmount stopAdjustment = DecimalAmount.of(atrDiscrete.dividedBy(2, Remainder.ROUND_EVEN));
            //orderService.adjustStopLoss(bestAsk.getPrice(), stopAdjustment);
            if (adjustStops)
                updateLongStops(bestAsk);
            if (targetDiscrete != null && adjustStops)
                updateLongTarget(getNetPosition(market, interval), bestAsk, targetDiscrete);

            //orderService.adjustStopLoss((atrDiscrete.dividedBy(2, Remainder.ROUND_EVEN)).negate());
            // longOrder.withLimitPrice(null);
            orderList.add(longOrder);

            //(portfolioService.getBaseCashBalance(portfolio.getBaseAsset()).compareTo(startingOrignalBal) > 0) ? portfolioService
            //      .getBaseCashBalance(portfolio.getBaseAsset()) : startingOrignalBal;
            // log.debug(this.getClass().getSimpleName() + ":BuildEnterShortOrders - setting previousBal to : " + previousBal );

        } else {
            //updateStops(OTEBal, bestAsk);
            log.info("Long Entry Prevented for interval " + interval + " as total cost: " + totalCost + " is greater than cash balace: " + cashBal + "at "
                    + context.getTime());
            revertPositionMap(market);
            // return null;
        }
        return orderList;
        /*else {

            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            // updateStops(OTEBal, bestAsk);
            log.info("Long Entry Prevented for interval " + interval + " as postion units : " + positionUnits + " is greater than max units of: "
                    + maxPositionUnits + "at " + context.getTime());
            revertPositionMap(market);
            return null;
        }*/
    }

    @SuppressWarnings("ConstantConditions")
    public synchronized ArrayList<Order> buildEnterLongOrders(double interval, double scaleFactor, double entryPrice, ExecutionInstruction execInst,
            double targetPrice, boolean adjustStops, Collection<SpecificOrder> longOpenOrders, Market market, @Nullable Market pairMarket) {
        Amount maxPositionUnits;
        Asset tradedCCY = (market.getTradedCurrency(market) == null) ? market.getQuote() : market.getTradedCurrency(market);

        // I am buying so I ask> bid, so I buy at the ask
        //BTC/USD
        //ETH/BTC
        // USD/USD BTC/USD
        //FUtures listing->BTC/USD
        //for eth/btc -> BTC/USD
        Listing listing = Listing.forPair(tradedCCY, portfolio.getBaseAsset());

        //for ETH.BTC, tradedListing = ETH/USD (traded rate)
        // for BTC.USD tradedListing = BTC/USD
        // futures tradedListing ->USD/USD
        // for eth/BTC -> ETH/USD
        Listing tradedListing = Listing.forPair(market.getBase(), portfolio.getBaseAsset());

        Offer rate = quotes.getImpliedBestAskForListing(listing);
        Offer baseRate = quotes.getImpliedBestAskForListing(tradedListing);

        //double atr = getAskATR(market);
        Offer bestAsk = (execInst == (ExecutionInstruction.TAKER)) ? quotes.getLastAskForMarket(market) : quotes.getLastBidForMarket(market);
        double volatility = getVol(market, interval);
        double priceVolatility = getPriceVol(market, getVolatilityInterval());
        double pricePointsVolatility = getPricePointsVol(market, getVolatilityInterval());
        if (getShortLow(market, interval) == 0 || volatility == 0 || priceVolatility == 0) {
            log.info("Long Entry Prevented for interval " + interval + " as no low at: " + context.getTime());
            revertPositionMap(market);
            //  || (positionMap.get(market) != null && positionMap.get(market).getType() == PositionType.ENTERING))
            return null;
        }

        double atr = (bestAsk.getPriceCountAsDouble() - getShortLow(market, interval));
        /// volatility) * 100;
        //double atr = atrStop * getVol(market, interval);
        double avg = getAvg(market);
        //double vol = getVol(market);
        log.debug("Long Entry Trigger for interval " + interval + " with atr: " + atr + " atrStop " + atrStop + " average " + avg + " and volatility "
                + volatility + " priceVolatility " + priceVolatility + " scaleFactor " + scaleFactor);
        // log.debug("Long Entry Trigger for interval " + interval + " with atr: " + atr + " and atrStop " + atrStop + " and average " + avg + " and volatility " + getVol(market, interval));
        Amount positionUnits = DecimalAmount.ONE;
        Amount positionVolume = DecimalAmount.ZERO;
        Amount workingLongVolume = DecimalAmount.ZERO;
        if (previousBal == null || previousBal.isZero()) {
            previousBal = getBaseLiquidatingValue();
            previousRestBal = previousBal;
        }
        if (getStartingOrignalBal(market) == null && previousBal != null && !previousBal.isZero())
            startingOrignalBal = previousRestBal;
        // buy order sp hit ask
        //buy entry to can take ask or make bid + increment
        //bids={10.0@606.81;2.0@606.57;12.0@606.45;1.0@605.67;87.0@605.66} asks={-96.0@607.22;-64.0@607.49;-121.0@607.51;-4.0@607.59;-121.0@607.79}

        //  Offer bestAsk = quotes.getLastAskForMarket(market);
        if ((bestAsk == null || bestAsk.getPriceCount() == 0)) {
            log.info("Long Entry Prevented for interval " + interval + " as no bid at: " + context.getTime());
            revertPositionMap(market);
            //  || (positionMap.get(market) != null && positionMap.get(market).getType() == PositionType.ENTERING))
            return null;
        }
        // what do we need to move teh stop by to only get a 15% loss?

        //   return buildExitShortOrders.buildOrders()
        //positionMap.put(positionUpdate.getMarket(), positionUpdate.getType());

        //		Collection<Position> positions = portfolioService.getPositions(bestBid.getMarket().getBase(), bestBid.getMarket().getExchange());
        //		Iterator<Position> itp = positions.iterator();
        //		while (itp.hasNext()) {f
        //			Position position = itp.next();
        //			if (position.isShort())
        //				//return buildExitShortOrders();
        //				return null;
        //		}

        // need to caculate if the stops needs to be moves such that we only loose x% of oTE
        DiscreteAmount atrDiscrete = (new DiscreteAmount((long) (atr), market.getPriceBasis()));
        double contractSize = (market.getContractSize(market));

        DiscreteAmount limitPrice = bestAsk.getPrice().increment(getSlippagePips(bestAsk.getPrice()));
        Amount dollarsPerPoint = (limitPrice.increment().minus(limitPrice))
                .times(market.getMultiplier(market, limitPrice, limitPrice.increment()), Remainder.ROUND_EVEN).times(contractSize, Remainder.ROUND_EVEN)
                .times(1 / limitPrice.getBasis(), Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);
        //   : DecimalAmount.ONE;
        //tradedRate.getPrice();

        if (dollarsPerPoint == null || dollarsPerPoint.isZero()) {
            log.info("Long Entry Prevented for interval " + interval + " as dollarsPerPoint is null at: " + context.getTime());
            revertPositionMap(market);

            return null;

        }
        Amount blockValue = dollarsPerPoint.times(0.01, Remainder.ROUND_EVEN).times(limitPrice, Remainder.ROUND_EVEN);

        //new DiscreteAmount((long) (entryPrice), bestAsk.getMarket().getPriceBasis());
        //bestBid.getPrice();
        // if ((limitPrice.getCount() - entryPrice) / atrDiscrete.getCount() < 1 / atrStop)
        //   return null;
        DiscreteAmount stopDiscrete = new DiscreteAmount((long) (pricePointsVolatility * atrStop), market.getPriceBasis());
        if (stopDiscrete.isZero()) {
            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            log.info("Long Entry Prevented for interval " + interval + " as ATR is zero at: " + context.getTime());
            revertPositionMap(market);
            //(OTEBal, bestAsk);
            return null;
        }

        if (rawSignals && orderService instanceof MockOrderService) {

            if ((getNetPosition(market, interval) != null && getNetPosition(market, interval).getLongVolume() != null) || !longOpenOrders.isEmpty()) {
                Amount openPostionVolume = getNetPosition(market, interval).getLongVolume();

                //  positionVolume = getNetPosition(market).getLongVolume();
                positionVolume = getNetPosition(market, interval).getOpenVolume();
                log.debug("Long Entry for interval " + interval + " current position volume:" + positionVolume);

                if (positionVolume.isNegative())
                    log.error("long postion break");
                if (openPostionVolume != null && positionVolume.compareTo(openPostionVolume) != 0) {
                    // net postion uses all fills, long volume is open && long + close && short || postion Effect ==null
                    // long only uses fills on long side.
                    // 2016-09-25 08:20:53 [pool-14-thread-2] INFO  org.cryptocoinpartners.portfolio - long postion break Net Position:Id=dc9c6e72-0f7e-4119-93fb-a1115a46b44c,Exchange=OKCOIN_THISWEEK,, Short Qty=0,, Short Avg Price=777.7700000000000000000000000000000000,, Short Avg Stop Price=0E-34,Long Qty=-4,Long Avg Price=0,Long Avg Stop Price=0, Net Qty=-4 Vol Count=-4,  Entry Date=, Instrument=BTC Long postion:Id=394b8fc6-c139-4164-9028-6d8747292508,Exchange=null,, Short Qty=0,, Short Avg Price=0,, Short Avg Stop Price=0,Long Qty=0,Long Avg Price=0,Long Avg Stop Price=0, Net Qty=0 Vol Count=0,  Entry Date=, Instrument=null

                    log.info("long postion break Net Position:" + getNetPosition(market, interval) + " Long postion:" + getLongPosition(market, interval));
                    log.info("net position fills:" + getNetPosition(market, interval).getFills());
                    log.info("long position fills :" + getLongPosition(market, interval).getFills());
                    // log.info("long postion break");
                    positionVolume = getNetPosition(market, interval).getLongVolume();
                    openPostionVolume = getLongPosition(market, interval).getOpenVolume();
                }

            } else {
                log.info("Long Entry Prevented for interval " + interval + " as already long " + getNetPosition(market, interval) + "with working orders "
                        + longOpenOrders + " at: " + context.getTime());
                revertPositionMap(market);

                return null;
            }

            DiscreteAmount orderDiscrete = new DiscreteAmount(Long.parseLong("1"), market.getVolumeBasis());

            // DiscreteAmount.(BigDecimal.ONE, market.getVolumeBasis());
            DecimalAmount maxAssetPosition = DecimalAmount.of(BigDecimal.ONE);

            log.debug("long entry for interval " + interval + " order order discrete: " + BigDecimal.ONE.negate() + " position volume: " + positionVolume
                    + " max asset position : " + maxAssetPosition);
            orderDiscrete = (orderDiscrete.compareTo(maxAssetPosition.minus(getLongPosition(market, interval).getOpenVolume().abs())) >= 0) ? (maxAssetPosition
                    .minus(getLongPosition(market, interval).getOpenVolume().abs())).toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR) : orderDiscrete;

            if (orderDiscrete.isZero() || orderDiscrete.isNegative()) {
                revertPositionMap(market);

                return null;
            }

            log.debug(this.getClass().getSimpleName() + ":BuildEnterLongOrders - trading with raw singals and fixed order size of one");

            GeneralOrder longOrder = generalOrderFactory
                    .create(context.getTime(), portfolio, market, orderDiscrete.asBigDecimal(), FillType.TRAILING_STOP_LOSS);
            longOrder.withComment(interval + " Long Entry Order").withStopAmount(stopDiscrete.asBigDecimal()).withTimeToLive(timeToLive)
            // .withPositionEffect(PositionEffect.OPEN);
                    .withPositionEffect(PositionEffect.OPEN).withExecutionInstruction(execInst);
            longOrder.withOrderGroup(interval);
            longOrder.withLimitPrice(limitPrice.asBigDecimal());
            ArrayList<Order> orderList = new ArrayList<Order>();
            // longOrder.withLimitPrice(null);
            orderList.add(longOrder);
            return orderList;

        }

        // we need to get the balance of the traded currency on this exchange to  to make sure we have enough cash, not balance for whole strategy
        Amount cashBal = portfolioService.getAvailableBaseBalance(tradedCCY, market.getExchange());
        //    Amount OTEBal = portfolioService.getBaseUnrealisedPnL(market.getTradedCurrency(market));
        // need to caculate if the stops needs to be moves such that we only loose x% of oTE

        //DecimalAmount stopAdjustment = DecimalAmount.of(atrDiscrete.dividedBy(2, Remainder.ROUND_EVEN));
        // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
        // we need to get the balance of the traded currency on this exchange to  to make sure we have enough cash, not balance for whole strategy
        //Amount totalBal = cashBal.plus(portfolioService.getUnrealisedPnL(market.getTradedCurrency()));
        Amount totalBal = cashBal;

        DiscreteAmount targetDiscrete;

        if (targetPrice == 0) {
            targetDiscrete = new DiscreteAmount((long) (atrTarget * atr), market.getPriceBasis());
        } else {
            targetDiscrete = (DiscreteAmount) (new DiscreteAmount((long) (targetPrice), market.getPriceBasis())).minus(limitPrice);
            // stopDiscrete = new DiscreteAmount((long) (targetDiscrete.getCount() * atrStop), bestAsk.getMarket().getPriceBasis());

        }
        if (targetDiscrete.isNegative())
            return null;
        Amount atrUSDDiscrete = atrDiscrete.times(dollarsPerPoint, Remainder.ROUND_CEILING);

        //  0.00106651 is the ATR, and the price is 0.02111778 BTC per ether and 1 BTC is 456. 454.49 USD, so 9.59 $ per ether.

        //so 0.01022 ATRUSD
        //   Amount totalBalUSD = (totalBal.times(baseRate.getPrice(), Remainder.ROUND_FLOOR));
        //TODO need to compare the notional to the release PnL not trading balance, as we are limiting the wining positions too much
        //50,000, loose 1,000, reduce notaional to 48000 when scaling notional balance
        //50,000, loose 1,000, reduce notaional to 40000 when scaling trading balance
        // Amount baseAmount;
        Amount PercentProfit = DecimalAmount.ZERO;
        Amount notionalBaseBalance = getOriginalBaseNotionalBalance(market);
        //   Amount notionalBaseBalance = getOriginalBaseNotionalBalance(market).times(scaleFactor, Remainder.ROUND_EVEN);
        // so each $ in rela losses reduced notinal by $y

        if (riskManageNotionalBalance) {
            Amount currentBal = getBaseLiquidatingValue().times(getMarketAllocations().get(market), Remainder.ROUND_EVEN);
            notionalBaseBalance = (currentBal.compareTo(previousBal) < 0) ? previousBal.minus((previousBal.minus(getBaseLiquidatingValue())).times(
                    getLossScalingFactor(), Remainder.ROUND_EVEN)) : currentBal;

            if (previousBal == null)
                previousBal = currentBal;
            else
                previousBal = (currentBal.compareTo(previousBal) > 0) ? currentBal : previousBal;
            //  Amount PercentProfit = DecimalAmount.ONE.minus(getBaseCashBalance().times(startingOrignalBal.invert(), Remainder.ROUND_EVEN));
        } else {
            PercentProfit = DecimalAmount.ONE.minus(getBaseLiquidatingValue().times(previousBal.invert(), Remainder.ROUND_EVEN));

            //PercentProfit = DecimalAmount.ONE.minus(getBaseCashBalance().times(getStartingOrignalBal(market).invert(), Remainder.ROUND_EVEN));
            //Does not consider loss of cureent working orders!
            if (!DecimalAmount.ONE.minus(PercentProfit.times(getLossScalingFactor(), Remainder.ROUND_EVEN)).isPositive())
                return null;
            notionalBaseBalance = (PercentProfit.isPositive()) ? notionalBaseBalance.times(
                    DecimalAmount.ONE.minus(PercentProfit.times(getLossScalingFactor(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN) : notionalBaseBalance;
        }

        if (portfolio.getBaseNotionalBalance().times(getMarketAllocations().get(market), Remainder.ROUND_EVEN).compareTo(notionalBaseBalance) != 0) {
            portfolio.setBaseNotionalBalanceCount(notionalBaseBalance.toBasis(portfolio.getBaseAsset().getBasis(), Remainder.ROUND_EVEN).getCount());
            portfolio.merge();
        }
        if (getStartingBaseNotionalBalance(market) == null)
            setStartingBaseNotionalBalance(notionalBaseBalance);

        if (!notionalBaseBalance.isPositive()) {

        }
        //reinvet profit
        // originalNotionalBalanceUSD.times(DecimalAmount.ONE.minus(PercentProfit), Remainder.ROUND_EVEN);
        //    notionalBalanceUSD = notionalBalanceUSD.times(limitPrice.divide(basePrice.asBigDecimal(), Remainder.ROUND_EVEN), Remainder.ROUND_EVEN);
        notionalBaseBalance = notionalBaseBalance.times(scaleFactor, Remainder.ROUND_EVEN);
        log.debug(this.getClass().getSimpleName() + ":BuildEnterLongOrders for interval " + interval + " at " + context.getTime() + " - notionalBalanceUSD: "
                + portfolio.getBaseNotionalBalance() + " notionalBaseBalance " + notionalBaseBalance + " Cash Balance: " + getBaseLiquidatingValue()
                + " startingOrignalBal: " + getStartingOrignalBal(market) + " originalNotionalBalanceUSD: " + getOriginalBaseNotionalBalance(market)
                + " lossScalingFactor: " + getLossScalingFactor() + " PercentProfit: " + PercentProfit + " scalingFactor " + scaleFactor);

        notionalBalance = notionalBaseBalance.times(rate.getPrice().invert(), Remainder.ROUND_EVEN);
        log.debug(this.getClass().getSimpleName() + ":BuildEnterLongOrders for interval " + interval + " - notionalBalance: " + notionalBalance
                + " notionalBalanceUSD: " + notionalBaseBalance + " tradedRate: " + baseRate.getPrice());

        //TODO Building postions too quickly  to max position size, so when they go wrong we make a big loss. need to ignore single is prvious trade wasa winnder
        if (!notionalBaseBalance.isPositive() || atrUSDDiscrete.isZero() || stopDiscrete.isZero()) {
            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            log.info("Long Entry Prevented for interval " + interval + " at " + context.getTime() + " as atrUSDDiscrete: " + atrUSDDiscrete
                    + " notionalBaseBalance:" + notionalBaseBalance);
            revertPositionMap(market);
            //(OTEBal, bestAsk);
            return null;
        }

        Amount maxAssetPosition = ((notionalBaseBalance.times(getVolatilityTarget(), Remainder.ROUND_FLOOR)).dividedBy(Math.sqrt(365.0), Remainder.ROUND_FLOOR))
                .dividedBy(blockValue.times(priceVolatility, Remainder.ROUND_FLOOR), Remainder.ROUND_FLOOR);

        //   Amount maxAssetPosition = ((notionalBaseBalance.times(getVolatilityTarget(), Remainder.ROUND_FLOOR)).dividedBy(Math.sqrt(365.0), Remainder.ROUND_FLOOR))
        //         .dividedBy(blockValue.times(priceVolatility, Remainder.ROUND_FLOOR), Remainder.ROUND_FLOOR);

        //    (MaxUnits.times(unitSize, Remainder.ROUND_FLOOR)).toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR);
        //  percentEquityRisked/maxLossTarget
        Amount unitSize = (maxAssetPosition.dividedBy((maxLossTarget / percentEquityRisked), Remainder.ROUND_FLOOR).compareTo(maxAssetPosition) < 0) ? maxAssetPosition
                : (maxAssetPosition.dividedBy((maxLossTarget / percentEquityRisked), Remainder.ROUND_FLOOR));
        //((notionalBaseBalance).times(percentEquityRisked, Remainder.ROUND_EVEN)).dividedBy((atrUSDDiscrete),

        //(atrUSDDiscrete.times(market.getContractSize(), Remainder.ROUND_EVEN)),

        //(atrUSDDiscrete.times((limitPrice.invert().times(market.getContractSize(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN)),
        //  atrUSDDiscrete, 
        //          Remainder.ROUND_EVEN);
        log.debug("Long Entry unit size for interval " + interval + " :" + unitSize + "atr usd discrete: " + atrUSDDiscrete + " dollars per point: "
                + dollarsPerPoint + " national USD balance:" + notionalBaseBalance);

        //ETH(base)/BTC(quote) (traded BTC)
        // BTC(base)/USD(quote) (traded USD)
        //

        //    if (market.getTradedCurrency(market).equals(market.getQuote())) {
        //      unitSize = unitSize.dividedBy(limitPrice, Remainder.ROUND_CEILING);
        //precision = BigDecimal.valueOf(market.getTradedCurrency().getBasis());
        // }
        //   ((notionalBalanceUSD).times(percentEquityRisked, Remainder.ROUND_EVEN)).dividedBy(atrUSDDiscrete,

        //.times(market.getContractSize(), Remainder.ROUND_EVEN)),

        //(atrUSDDiscrete.times((limitPrice.invert().times(market.getContractSize(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN)),
        //        atrUSDDiscrete,

        //  Remainder.ROUND_EVEN);
        if (unitSize.isZero()) {
            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            // updateStops(OTEBal, bestAsk);
            log.info("Long Entry Prevented for interval " + interval + " as units size is zero at: " + context.getTime());
            revertPositionMap(market);
            return null;
        }
        //log.info("ATR: " + atrUSDDiscrete.toString() + " unit size: " + unitSize.toString());

        // if (unitSize.compareTo(maxUnitSize) > 0)
        //     unitSize = maxUnitSize;
        //  logger.info("unit size:" + unitSize.toString());
        //if (portfolioService.getPositions().isEmpty()
        //	|| (totalBal.minus(portfolioService.getPositions().get(0).getVolume().abs().times(bestBid.getPrice(), Remainder.ROUND_EVEN))).isPositive()) {
        if (getNetPosition(market, interval) != null && getNetPosition(market, interval).getLongVolume() != null) {
            Amount openPostionVolume = getNetPosition(market, interval).getLongVolume();

            //  positionVolume = getNetPosition(market).getLongVolume();
            // so I am trding ETH/BTC so QUote CCY=Traced CCY
            // when trading BTC/USD, the quote CCy!+Traded CCY
            // so when trading futures I am entering into a long positions for 
            positionVolume = getLongPosition(market, interval).getOpenVolume();
            log.debug("Long Entry for interval " + interval + " current position volume:" + positionVolume);

            if (positionVolume.isNegative())
                log.info("long postion break");
            if (openPostionVolume != null && positionVolume.compareTo(openPostionVolume) != 0) {

                log.info("long postion break Net Position:" + getNetPosition(market, interval) + " Long postion:" + getLongPosition(market, interval));

                // log.info("long postion break");
                positionVolume = getNetPosition(market, interval).getLongVolume();
                openPostionVolume = getLongPosition(market, interval).getOpenVolume();
            }
            positionUnits = positionVolume.dividedBy(unitSize, Remainder.ROUND_EVEN);
        }
        //if (position != null && position.getAvgStopPrice().invert().isZero())
        //     log.info("issues wiht exit prices");
        //set exit price to average stop price of current position
        // TODO something is up here.
        Amount exitPrice = limitPrice.minus(stopDiscrete);
        log.debug("long entry for interval " + interval + " order exit price: " + exitPrice + " long entry order limit price: " + limitPrice);

        //Price differnte between current price and the exit price
        //TODO need to check if base ccy are the same
        Amount priceDiff;
        Amount positionPriceDiff;
        Amount openOrderPriceDiff;
        // ETH(base)/BTC trade CCy+1Quote
        // USD(base)/BTC (quote) traded CCY=BTC
        //USD/LTC ()
        /* if ((market.getTradedCurrency(market).equals(market.getQuote()))) {
             // we have BTC (BASE)/USD(QUOTE) (traded BTC)->  LTC(BASE)//USD(QUOTE) (traded BTC)
             // need to invert and revrese the prices if the traded ccy is not the quote ccy

             priceDiff = (exitPrice.compareTo(limitPrice) < 0) ? (exitPrice.invert()).minus(limitPrice.invert()) : (limitPrice.invert()).minus(exitPrice
                     .invert());
             positionPriceDiff = (getNetPosition(market).getLongAvgStopPrice().compareTo(getNetPosition(market).getLongAvgPrice()) < 0) ? (getNetPosition(market)
                     .getLongAvgStopPrice().invert()).minus(getNetPosition(market).getLongAvgPrice().invert()) : (getNetPosition(market).getLongAvgPrice()
                     .invert()).minus(getNetPosition(market).getLongAvgStopPrice().invert());

             openOrderPriceDiff = (Order.getOpenAvgStopPrice(longOpenOrders).compareTo(Order.getOpenAvgPrice(longOpenOrders)) < 0) ? (Order
                     .getOpenAvgStopPrice(longOpenOrders).invert()).minus(Order.getOpenAvgPrice(longOpenOrders).invert()) : (Order
                     .getOpenAvgPrice(longOpenOrders).invert()).minus(Order.getOpenAvgStopPrice(longOpenOrders).invert());

         } else {*/
        // exit price less than limit price
        priceDiff = (exitPrice.compareTo(limitPrice) < 0) ? (limitPrice).minus(exitPrice) : (exitPrice).minus(limitPrice);
        positionPriceDiff = (getNetPosition(market, interval).getLongAvgStopPrice().compareTo(getNetPosition(market, interval).getLongAvgPrice()) < 0) ? (getNetPosition(
                market, interval).getLongAvgPrice()).minus(getNetPosition(market, interval).getLongAvgStopPrice()) : (getNetPosition(market, interval)
                .getLongAvgStopPrice()).minus(getNetPosition(market, interval).getLongAvgPrice());

        openOrderPriceDiff = (Order.getOpenAvgStopPrice(longOpenOrders).compareTo(Order.getOpenAvgPrice(longOpenOrders)) < 0) ? (Order
                .getOpenAvgPrice(longOpenOrders)).minus(Order.getOpenAvgStopPrice(longOpenOrders)) : (Order.getOpenAvgStopPrice(longOpenOrders)).minus(Order
                .getOpenAvgPrice(longOpenOrders));

        //Max  loss in traded currency current position
        //Amount of loss in quote currency

        //BTC I will loss on exisit postions
        Amount lossAmountPerShare = priceDiff;
        //.plus(priceDiff.times(
        //   market.getMultiplier(market, limitPrice, exitPrice), Remainder.ROUND_EVEN).times(market.getContractSize(market), Remainder.ROUND_EVEN));

        // lossAmount = priceDiff.times(market.getContractSize(), Remainder.ROUND_EVEN).times(exitPrice, Remainder.ROUND_EVEN);
        // currnet max loss = ( 1/entry price - 1/exit price ) * volume * contract size.
        // the loss amount is set to the exit price, which is set to the current postion average stop price, which is not what we want
        //$$ value of of losses of existing postion.
        //         ETH/BTC, BTC/USD  USD=>USD
        //  USD=>BTC/USD
        // BTC/USD (pnL accured in traded CCY of BTC)
        //ETH/BTC (pnl accureved in traded CCT of btc) should be accured in quote currency i.e. the traded CCY.
        // if (market.getContractSize(market) != 1.0)

        //   baseLossAmount = (baseLossAmount.isZero()) ? DecimalAmount.ONE : priceDiff
        //         .times(market.getMultiplier(market, limitPrice, exitPrice), Remainder.ROUND_EVEN)
        //       .times(market.getContractSize(market), Remainder.ROUND_EVEN).times(exitPrice, Remainder.ROUND_EVEN)
        //     .times(rate.getPrice(), Remainder.ROUND_EVEN).times(baseRate.getPrice(), Remainder.ROUND_EVEN);
        //else
        //  lossAmount = (lossAmount.isZero()) ? DecimalAmount.ONE : priceDiff.times(market.getMultiplier(market, limitPrice, exitPrice), Remainder.ROUND_EVEN)
        //        .times(market.getContractSize(market), Remainder.ROUND_EVEN);
        //  .times(baseRate.getPrice(), Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);

        // currnet max loss = ( 1/entry price - 1/exit price ) * volume * contract size.
        // this takes into consideraton the currnet loss on existing positions.
        if (getNetPosition(market, interval) != null && getNetPosition(market, interval).getLongVolume() != null
                && getNetPosition(market, interval).getLongVolume().isPositive())
            log.debug("checkpoint");
        //  Amount currentMarketLossAmount = (limitPrice.minus(stopDiscrete).times(market.getContractSize(), Remainder.ROUND_EVEN)).times(
        //        limitPrice.minus(stopDiscrete), Remainder.ROUND_EVEN);
        Amount currentMaxLossAmount;
        //     ETH/BTC (traded BTC)
        //   BTC/USD
        Amount weightedExitPrice = DecimalAmount.ONE;
        Amount weightedEntryPrice = DecimalAmount.ONE;
        //   BTC/USD (BTC is traded), ETH/BTC (ETH is traded)
        // if (!(market.getTradedCurrency(market).equals(market.getQuote())))

        //TODO check theis the weight price when to coauclauted and applydoes not seem right.
        weightedExitPrice = //(market.getContractSize(market) == 1 ? DecimalAmount.ONE : 
        (Order.getOpenVolume(longOpenOrders, market).plus(positionVolume)).isZero() ? exitPrice : (((getNetPosition(market, interval).getLongAvgStopPrice()
                .times(positionVolume, Remainder.ROUND_EVEN)).plus(Order.getOpenAvgStopPrice(longOpenOrders).times(Order.getOpenVolume(longOpenOrders, market),
                Remainder.ROUND_EVEN))).dividedBy((Order.getOpenVolume(longOpenOrders, market).plus(positionVolume)), Remainder.ROUND_EVEN));
        //);
        weightedExitPrice = weightedExitPrice.times(1 - slippage, Remainder.ROUND_FLOOR);
        weightedEntryPrice =
        //market.getContractSize(market) == 1 ? DecimalAmount.ONE : 
        (Order.getOpenVolume(longOpenOrders, market).plus(positionVolume)).isZero() ? limitPrice : (((getNetPosition(market, interval).getLongAvgPrice().times(
                positionVolume, Remainder.ROUND_EVEN)).plus(Order.getOpenAvgPrice(longOpenOrders).times(Order.getOpenVolume(longOpenOrders, market),
                Remainder.ROUND_EVEN))).dividedBy((Order.getOpenVolume(longOpenOrders, market).plus(positionVolume)), Remainder.ROUND_EVEN));
        // loss in quote currenent if no traded ccy is left
        Amount currentMaxLoss = (weightedExitPrice.minus(weightedEntryPrice)).abs().times(
                (getLongPosition(market, interval).getOpenVolume().plus(Order.getOpenVolume(longOpenOrders, market))), Remainder.ROUND_EVEN);
        if (lossAmountPerShare.isZero()) {
            log.info("BulidEnterLongOrders: For interval " + interval + " exiting as Loss Amount is Zero." + " priceDiff: " + priceDiff + "multiplier: "
                    + market.getMultiplier(market, limitPrice, exitPrice) + " contractSize: " + market.getContractSize(market) + " currentMaxLoss: "
                    + currentMaxLoss + " exitPrice: " + exitPrice);
            revertPositionMap(market);

            return null;
        }
        //, remainderHandler)))
        // if (market.getTradedCurrency(market).equals(market.getQuote()))
        //   currenMaxLoos is the price diff x the volume, so we need to times it by mutlplier

        Amount baseCurrentMaxLossAmount = currentMaxLoss.times(market.getMultiplier(market, weightedEntryPrice, weightedExitPrice), Remainder.ROUND_EVEN)
                .times(contractSize, Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);

        //                .times(weightedExitPrice, Remainder.ROUND_CEILING)).times(weightedExitPrice, Remainder.ROUND_EVEN);

        currentMaxLossAmount = (notionalBaseBalance.times(maxLossTarget, Remainder.ROUND_EVEN)).minus(baseCurrentMaxLossAmount);
        //  else
        //    currentMaxLossAmount = (notionalBaseBalance.times(maxLossTarget, Remainder.ROUND_EVEN)).minus(currentMaxLoss.times(weightedExitPrice,
        //          Remainder.ROUND_CEILING));
        //TODO need to add a loop over the cancel orders are re-submit at new limit price to ensure any previously triggered units are added back to market and filled if they meet the volume allowence.

        //    Amount dollarsPerPoint = (limitPrice.increment().minus(limitPrice))
        //          .times(market.getMultiplier(market, limitPrice, limitPrice.increment()), Remainder.ROUND_EVEN).times(contractSize, Remainder.ROUND_EVEN)
        //        .times(1 / limitPrice.getBasis(), Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);
        //for cash
        //price diff X rate
        // for dervies
        // price diff x multipliter * contract size * rate

        Amount MaxUnits = ((currentMaxLossAmount).dividedBy(
                ((lossAmountPerShare.times(dollarsPerPoint, Remainder.ROUND_CEILING)).times(unitSize, Remainder.ROUND_FLOOR)), Remainder.ROUND_FLOOR)).toBasis(
                1, Remainder.ROUND_FLOOR);

        //  DiscreteAmount maxAssetPosition = (MaxUnits.times(unitSize, Remainder.ROUND_FLOOR)).toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR);
        //.plus(getLongPosition(market).getOpenVolume().plus(Order.getOpenVolume(longOpenOrders, market)));

        // (maxAssetPosition.toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR));

        // (lossBalance).dividedBy(((priceDiff).times(market.getContractSize(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN);
        log.debug("Long Entry for interval " + interval + " Max Asset Position: " + maxAssetPosition + "notional balance " + notionalBalance
                + " notional base balance: " + notionalBaseBalance + " maxLossTarget : " + maxLossTarget + " lossAmount" + lossAmountPerShare
                + "baseCurrentMaxLossAmount: " + baseCurrentMaxLossAmount + "currentMaxLossAmount: " + currentMaxLossAmount + " price diff: " + priceDiff
                + " multiplier: " + market.getMultiplier(market, limitPrice, exitPrice) + " contract size: " + market.getContractSize(market) + "max units:"
                + MaxUnits);

        if (!maxAssetPosition.minus(positionVolume).isPositive()) {
            revertPositionMap(market);
            log.info("Long Entry Prevented for interval " + interval + " as  positionVolume :" + positionVolume + " is greater than maxAssetPosition: "
                    + maxAssetPosition);
            if (maxAssetPosition.minus(positionVolume.abs()).isNegative()) {
                //so we are short and we have too many lots, so we need to buy some.
                // the amount we need to buy is 
                Amount reBalVol = maxAssetPosition.minus(positionVolume);
                SpecificOrder positionRebalOrder = specificOrderFactory.create(context.getTime(), portfolio, market, reBalVol, "Long Position ReBal Order");

                positionRebalOrder.withPositionEffect(PositionEffect.CLOSE).withExecutionInstruction(ExecutionInstruction.TAKER).withOrderGroup(interval);

                ArrayList<Order> orderList = new ArrayList<Order>();
                orderList.add(positionRebalOrder);

                return orderList;

                //              log.info("Long Entry Prevented for interval " + interval + " as  positionVolume :" + positionVolume + " is greater than maxAssetPosition: "
                //                    + maxAssetPosition);

            }
            return null;
        }

        Amount orderDiscrete = (unitSize.compareTo(maxAssetPosition.minus(positionVolume)) >= 0) ? maxAssetPosition.minus(positionVolume) : unitSize.toBasis(
                market.getVolumeBasis(), Remainder.ROUND_CEILING);
        log.debug("long entry for interval " + interval + " order order discrete: " + orderDiscrete + " position volume: " + positionVolume
                + " max asset position: " + maxAssetPosition + "unit size" + unitSize);
        /*        if (maxAssetPosition.compareTo((getLongPosition(market).getOpenVolume().plus(Order.getOpenVolume(longOpenOrders, market)))) < 0) {

                    log.info("long entry order order prevneted as current long position volume: " + positionVolume + " and open order volume: "
                            + Order.getOpenVolume(longOpenOrders, market) + " is greater than max position of " + maxAssetPosition);
                    return null;
                }*/
        //orderDiscrete = (DiscreteAmount) ((orderDiscrete.compareTo((maxAssetPosition).minus((getLongPosition(market).getOpenVolume().plus(Order.getOpenVolume(
        //  longOpenOrders, market))))) >= 0) ? (maxAssetPosition).minus(getLongPosition(market).getOpenVolume().plus(
        // Order.getOpenVolume(longOpenOrders, market))) : orderDiscrete);
        //orderDiscrete.roundedCountForBasis(amount, basis)
        //orderDiscrete.toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR);
        log.debug("long entry for interval " + interval + " order order discrete: " + orderDiscrete + " pongPosition: "
                + getLongPosition(market, interval).getOpenVolume() + "open volume" + Order.getOpenVolume(longOpenOrders, market) + " max asset position: "
                + maxAssetPosition);

        //DiscreteAmount orderDiscrete = maxAssetPosition.toBasis(bestBid.getMarket().getVolumeBasis(), Remainder.ROUND_EVEN);

        //      logger.info("max position:" + maxAssetPosition.toString() + " max loss:" + maxLossTarget + " notila balance: " + notionalBalance.toString());

        if (orderDiscrete.isZero() || orderDiscrete.isNegative()) {
            log.info("Long Entry for interval " + interval + " Prevented as currnet position: " + positionVolume + " is greater than max position: "
                    + maxAssetPosition + " at:" + context.getTime() + "with atr:" + atrDiscrete);
            log.trace(getNetPosition(market, interval).getFills().toString());

            revertPositionMap(market);
            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            //   updateStops(OTEBal, bestAsk);
            return null;
        }
        maxPositionUnits = maxAssetPosition.dividedBy(orderDiscrete, Remainder.ROUND_EVEN);

        //  if (orderDiscrete.isPositive() && (totalPosition.compareTo(maxPositionUnits) <= 0)) {
        if (orderDiscrete.isPositive()) {
            GeneralOrder longOrder = generalOrderFactory
                    .create(context.getTime(), portfolio, market, orderDiscrete.asBigDecimal(), FillType.TRAILING_STOP_LOSS);

            longOrder.withComment(interval + " Long Entry Order").withStopAmount(stopDiscrete.asBigDecimal()).withTimeToLive(timeToLive)
                    // .withPositionEffect(PositionEffect.OPEN);
                    .withTargetAmount(targetDiscrete.asBigDecimal()).withPositionEffect(PositionEffect.OPEN).withExecutionInstruction(execInst)
                    .withOrderGroup(interval);
            Collection<SpecificOrder> pendingOrders = (longOrder.isBid()) ? orderService.getPendingLongOrders() : orderService.getPendingShortOrders();
            Amount workingVolume = orderDiscrete;
            for (SpecificOrder workingOrder : pendingOrders)
                workingVolume = workingVolume.plus(workingOrder.getUnfilledVolume());
            // if I am buying, then I can buy at current best ask and sell at current best bid
            Book lastBook = quotes.getLastBook(market);
            log.info(this.getClass().getSimpleName() + ":getEnterLongOrders - For interval " + interval + " setting limit prices for market " + market
                    + " using lastBook" + lastBook);
            if (market.getSymbol().equals("OKCOIN_THISWEEK:LTC.USD.THISWEEK"))
                log.debug("test");

            Offer bestOffer = (longOrder.isBid()) ? lastBook.getBestAskByVolume(new DiscreteAmount(DiscreteAmount.roundedCountForBasis(
                    workingVolume.asBigDecimal(), market.getVolumeBasis()), market.getVolumeBasis())) : lastBook.getBestBidByVolume(new DiscreteAmount(
                    DiscreteAmount.roundedCountForBasis(workingVolume.asBigDecimal(), market.getVolumeBasis()), market.getVolumeBasis()));

            // this is short exit, so I am buy, so hitting the ask
            // loop down asks until the total quanity of the order is reached.
            // buying at ask!
            if (longOrder.getExecutionInstruction() != null && longOrder.getExecutionInstruction().equals(ExecutionInstruction.TAKER) && bestOffer != null
                    && bestOffer.getPrice() != null && bestOffer.getPrice().getCount() != 0) {
                // limitPrice = bestOffer.getPrice().increment(getSlippagePips(limitPrice));
                longOrder.withLimitPrice(limitPrice.increment(getSlippagePips(limitPrice)).asBigDecimal());
                //    longOrder.setFillType(FillType.MARKET);

                log.info(this.getClass().getSimpleName() + ":getEnterLongOrders - For interval " + interval + " setting limit price to best offer by volume"
                        + limitPrice + " for order " + longOrder);
            }
            // specificOrder.withLimitPrice(limitPrice);

            //I want to buy. so will buy at highest price

            //     longOrder.withLimitPrice(limitPrice.increment(slippage).asBigDecimal());
            //  if (execInst == ExecutionInstruction.MAKER)
            //    longOrder.withMarketPrice(limitPrice.increment(getSlippagePips(limitPrice)).asBigDecimal());
            //else
            longOrder.withLimitPrice(limitPrice);
            if (limit) {
                DiscreteAmount triggerPrice = (DiscreteAmount) (new DiscreteAmount((long) (atrTrigger * atr), market.getPriceBasis())).plus(bestAsk.getPrice());

                //DiscreteAmount triggerPrice = bestAsk.getPrice().increment(triggerBuffer);

                longOrder.withTargetPrice(triggerPrice.asBigDecimal());
            }

            // testOrder.persit();
            // GeneralOrderBuilder orderBuilder = order.create(context.getTime(), market, orderDiscrete.asBigDecimal(), FillType.STOP_LOSS)
            //       .withComment("Long Entry Order").withLimitPrice(limitPrice.asBigDecimal()).withStopAmount(stopDiscrete.asBigDecimal())
            // .withPositionEffect(PositionEffect.OPEN);
            //     .withTargetAmount(targetDiscrete.asBigDecimal()).withPositionEffect(PositionEffect.OPEN).withExecutionInstruction(execInst);
            // BUYINg so costs will be in ETHERS, which is the BASE CCY or the traded CCY, need to conver to the traed CCY using the BASE to TRADED CCY conversion
            // ETH/BTC
            // when i am buying, I am buying buying the base I am buying 100 ETH (base). the fees will be in the base CCY.

            Listing costListing = Listing.forPair(market.getBase(), tradedCCY);

            Offer costRate = quotes.getImpliedBestAskForListing(costListing);

            //  asdfasdf
            Amount totalCost = (FeesUtil.getMargin(longOrder).plus(FeesUtil.getCommission(longOrder))).negate()
                    .times(costRate.getPrice(), Remainder.ROUND_EVEN);
            // whyis total cost in the base CCY, when we are buying ethers with BTC, charge will be in eithers, i.e either the traded curreny or the 
            // whne I am selling ETH for BTC, the chagge will be in  BTC the traded currency or the quote CCY.
            //BTC balance
            cashBal = portfolioService.getAvailableBalance(tradedCCY, market.getExchange());
            if ((totalCost.compareTo(cashBal) > 0)) {
                Amount totalCostAmmount = (cashBal.isNegative()) ? totalCost.minus(cashBal) : totalCost;
                // get USD balance in BTC.
                cashBal = (portfolioService.getAvailableBalance(portfolio.getBaseAsset(), market.getExchange())).dividedBy(rate.getPrice(),
                        Remainder.ROUND_EVEN);
                if ((totalCost.compareTo(cashBal) > 0)) {
                    // Let's adjust quanity
                    Amount qtyScale = cashBal.dividedBy((totalCost.times(1.1, Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN);
                    //  longOrder.getVolume().times(qtyScale, Remainder.ROUND_EVEN);
                    Amount vol = (qtyScale.isZero()) ? longOrder.getVolume() : longOrder.getVolume().times(qtyScale, Remainder.ROUND_EVEN);
                    longOrder.setVolumeDecimal(vol.asBigDecimal());
                    totalCost = (FeesUtil.getMargin(longOrder).plus(FeesUtil.getCommission(longOrder))).negate();
                    if ((totalCost.compareTo(cashBal) > 0)) {

                        log.info("Long Entry Prevented for interval " + interval + "  as total cost: " + totalCost + " is greater than cash balace: " + cashBal
                                + "at " + context.getTime());
                        revertPositionMap(market);

                        // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
                        //updateStops(OTEBal, bestAsk);
                        return null;
                    }
                } else if (cashBal.isPositive() && cashBal.compareTo(totalCost) > 0) {

                    //neeed to transfer the total cost
                    Transaction initialCredit = transactionFactory.create(portfolio, market.getExchange(), tradedCCY, TransactionType.CREDIT, totalCostAmmount,
                            new DiscreteAmount(0, 0.01));
                    context.setPublishTime(initialCredit);

                    initialCredit.persit();

                    context.route(initialCredit);

                    Transaction initialDebit = transactionFactory.create(portfolio, market.getExchange(), portfolio.getBaseAsset(), TransactionType.DEBIT,
                            (totalCostAmmount.times(rate.getPrice(), Remainder.ROUND_EVEN)).negate(), new DiscreteAmount(0, 0.01));
                    context.setPublishTime(initialDebit);

                    initialDebit.persit();

                    context.route(initialDebit);

                    cashBal = portfolioService.getAvailableBalance(tradedCCY, market.getExchange()).plus(totalCostAmmount);

                }
            }

            if ((totalCost.compareTo(cashBal) <= 0)) {

                //DecimalAmount stopAdjustment = DecimalAmount.of(atrDiscrete.dividedBy(2, Remainder.ROUND_EVEN));
                //orderService.adjustStopLoss(bestAsk.getPrice(), stopAdjustment);
                if (adjustStops)
                    updateLongStops(bestAsk);
                if (targetDiscrete != null && adjustStops)
                    updateLongTarget(getNetPosition(market, interval), bestAsk, targetDiscrete);

                //orderService.adjustStopLoss((atrDiscrete.dividedBy(2, Remainder.ROUND_EVEN)).negate());
                ArrayList<Order> orderList = new ArrayList<Order>();
                // longOrder.withLimitPrice(null);
                orderList.add(longOrder);
                if (pairMarket != null) {
                    GeneralOrder pairOrder = generalOrderFactory.create(longOrder);
                    pairOrder.setVolume(longOrder.getVolume().negate());
                    pairOrder.setListing(longOrder.getListing());
                    pairOrder.setMarket(pairMarket);
                    longOrder.addChildOrder(pairOrder);
                    pairOrder.setParentOrder(longOrder);
                    //long entry to can take ask or make bid

                    Offer farBestAsk = (execInst == (ExecutionInstruction.TAKER)) ? quotes.getLastAskForMarket(pairMarket) : quotes
                            .getLastBidForMarket(pairMarket);

                    // if (execInst == ExecutionInstruction.MAKER)
                    //   pairOrder.withMarketPrice(limitPrice.increment(getSlippagePips(limitPrice)).asBigDecimal());
                    //else
                    pairOrder.withLimitPrice(farBestAsk.getPrice().decrement().asBigDecimal());
                    orderList.add(pairOrder);

                }

                //(portfolioService.getBaseCashBalance(portfolio.getBaseAsset()).compareTo(startingOrignalBal) > 0) ? portfolioService
                //      .getBaseCashBalance(portfolio.getBaseAsset()) : startingOrignalBal;
                // log.debug(this.getClass().getSimpleName() + ":BuildEnterShortOrders - setting previousBal to : " + previousBal );

                return orderList;
            } else {
                //updateStops(OTEBal, bestAsk);
                log.info("Long Entry Prevented for interval " + interval + " as total cost: " + totalCost + " is greater than cash balace: " + cashBal + "at "
                        + context.getTime());
                revertPositionMap(market);
                return null;
            }
        } else {

            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            // updateStops(OTEBal, bestAsk);
            log.info("Long Entry Prevented for interval " + interval + " as postion units : " + positionUnits + " is greater than max units of: "
                    + maxPositionUnits + "at " + context.getTime());
            revertPositionMap(market);
            return null;
        }

    }

    private synchronized void enterOrders(Market market, double entryPrice, double scaleFactor, double interval, ExecutionInstruction execInst, double forecast) {

        double targetPrice = entryPrice * atrTarget;
        Collection<Market> markets = new ArrayList<Market>();
        markets.add(market);
        Collection<SpecificOrder> openLongOrders = null;
        Collection<SpecificOrder> openShortOrders = null;
        boolean ordersCancelled = false;
        for (Market tradedMarket : markets) {

            if (forecast < 0) {
                // cancel these if entering short
                openShortOrders = orderService.getPendingShortOpenOrders(portfolio, market, interval);
                log.debug(this.getClass().getSimpleName() + ":enterOrders - Cancelling all short closing specific order for interval " + interval);

                orderService.handleCancelAllShortClosingSpecificOrders(portfolio, tradedMarket, interval);

                // cancle these if exiting long            
                log.debug(this.getClass().getSimpleName() + ":enterOrders - Cancelling all long open specific order for interval " + interval);
                orderService.handleCancelAllLongOpeningSpecificOrders(portfolio, market, interval);
                log.debug(this.getClass().getSimpleName() + ":enterOrders - Cancelling all long open general orders for interval " + interval);
                orderService.handleCancelAllLongOpeningGeneralOrders(portfolio, market, interval);
                if ((orderService.getPendingLongOpenOrders(portfolio, market, interval) == null || (orderService.getPendingLongOpenOrders(portfolio, market,
                        interval) != null && orderService.getPendingLongOpenOrders(portfolio, market, interval).isEmpty()))) {
                    openShortOrders.addAll(orderService.getPendingLongCloseOrders(portfolio, market, interval));
                    ordersCancelled = true;
                }

            } else if (forecast > 0) {
                openLongOrders = orderService.getPendingLongOpenOrders(portfolio, market, interval);
                log.debug(this.getClass().getSimpleName() + ":enterOrders - Cancelling all long closing specific order for interval " + interval);

                orderService.handleCancelAllLongClosingSpecificOrders(portfolio, tradedMarket, interval);

                // cancle these if exiting long            
                log.debug(this.getClass().getSimpleName() + ":enterOrders - Cancelling all short open specific order for interval " + interval);
                orderService.handleCancelAllShortOpeningSpecificOrders(portfolio, market, interval);
                log.debug(this.getClass().getSimpleName() + ":enterOrders - Cancelling all long open general orders for interval " + interval);
                orderService.handleCancelAllShortOpeningGeneralOrders(portfolio, market, interval);
                if ((orderService.getPendingShortOpenOrders(portfolio, market, interval) == null || (orderService.getPendingShortOpenOrders(portfolio, market,
                        interval) != null && orderService.getPendingShortOpenOrders(portfolio, market, interval).isEmpty()))) {
                    openLongOrders.addAll(orderService.getPendingShortCloseOrders(portfolio, market, interval));
                    ordersCancelled = true;
                }

                if (positionMap.get(tradedMarket) != null
                        && (positionMap.get(tradedMarket).getType() == (PositionType.ENTERING) || positionMap.get(tradedMarket).getType() == (PositionType.EXITING)))

                    revertPositionMap(tradedMarket);

                updatePositionMap(tradedMarket, PositionType.ENTERING);

                if (!ordersCancelled) {
                    log.info("Order Entry Prevented as have working orders");

                    revertPositionMap(tradedMarket);

                    return;
                }
            }

        }
        ArrayList<Order> orderList = buildEnterOrders(interval, scaleFactor, forecast, execInst, targetPrice, adjustStops, openLongOrders, openShortOrders,
                market);
        log.info("Forecast Processed");

        if (orderList == null) {
            //    revertPositionMap(market);
            return;
        }

        for (Order order : orderList)

            placeOrder(order);
    }

    private void enterShortOrders(double interval, double scaleFactor, double entryPrice, ExecutionInstruction execInst, Market market, Market pairMarket) {
        double targetPrice = entryPrice;
        Collection<Market> markets = new ArrayList<Market>();
        markets.add(market);
        if (pairMarket != null)
            markets.add(pairMarket);

        Collection<SpecificOrder> shortOpenOrders = orderService.getPendingShortOpenOrders(portfolio, market, interval);

        //Collection<SpecificOrder> shortOpenOrders = orderService.handleCancelAllShortOpeningSpecificOrders(portfolio, market);
        for (Market tradedMarket : markets) {

            orderService.handleCancelAllShortClosingSpecificOrders(portfolio, tradedMarket, interval);
            //  orderService.handleCancelAllShortOpeningGeneralOrders(portfolio, tradedMarket);
            // orderService.handleCancelAllShortOpeningSpecificOrders(portfolio, tradedMarket);

            if (positionMap.get(tradedMarket) != null
                    && (positionMap.get(tradedMarket).getType() == (PositionType.ENTERING) || positionMap.get(tradedMarket).getType() == (PositionType.EXITING)))

                revertPositionMap(tradedMarket);

            //if (!orderService.getPendingShortOpenOrders(portfolio).isEmpty())
            //cancellationService.submit(new handleCancelAllShortOpeningSpecificOrders(portfolio, market));

            updatePositionMap(tradedMarket, PositionType.ENTERING);

            if (!orderService.getPendingShortOpenOrders(portfolio, market, interval).isEmpty()
                    || !orderService.getPendingShortCloseOrders(portfolio, market, interval).isEmpty()) {
                log.info("Short Entry Prevented as have working Short orders");

                revertPositionMap(tradedMarket);

                return;
            }
        }

        ArrayList<Order> orderList = buildEnterShortOrders(interval, scaleFactor, entryPrice, execInst, targetPrice, adjustStops, shortOpenOrders, market,
                pairMarket);
        log.info("Long Low Indicator Processed");

        if (orderList == null) {
            //    revertPositionMap(market);
            return;
        }

        for (Order order : orderList)

            placeOrder(order);

    }

    @SuppressWarnings("ConstantConditions")
    public synchronized ArrayList<Order> buildEnterShortOrders(double interval, double scaleFactor, double entryPrice, ExecutionInstruction execInst,
            double targetPrice, boolean adjustStops, Collection<SpecificOrder> shortOpenOrders, Market market, @Nullable Market pairMarket) {
        //orderService.handleCancelAllOpeningSpecificOrders(portfolio, market);
        // revertPositionMap(market);
        Amount maxPositionUnits;
        Asset tradedCCY = (market.getTradedCurrency(market) == null) ? market.getQuote() : market.getTradedCurrency(market);

        //   Asset tradedCCY = (market.getTradedCurrency(market) == null) ? market.getBase() : market.getTradedCurrency(market);

        //for ETH.BTC, listing = BTC/USD (rate)
        // for BTC.USD listing = USD/USD
        Listing listing = Listing.forPair(tradedCCY, portfolio.getBaseAsset());

        //for ETH.BTC, tradedListing = ETH/USD (traded rate)
        // for BTC.USD tradedListing = BTC/USD
        Listing tradedListing = Listing.forPair(market.getQuote(), portfolio.getBaseAsset());

        Offer rate = quotes.getImpliedBestBidForListing(listing);
        Offer baseRate = quotes.getImpliedBestBidForListing(tradedListing);
        Offer bestBid = (execInst == (ExecutionInstruction.TAKER)) ? quotes.getLastBidForMarket(market) : quotes.getLastAskForMarket(market);

        // double atr = getBidATR(market);
        // double atr = getShortHigh(market);
        double volatility = getVol(market, interval);
        double priceVolatility = getPriceVol(market, getVolatilityInterval());
        double pricePointsVolatility = getPricePointsVol(market, getVolatilityInterval());

        if (getShortHigh(market, interval) == 0 || volatility == 0 || priceVolatility == 0) {
            log.info("Short Entry Prevented for interval " + interval + " as no low at: " + context.getTime());
            revertPositionMap(market);
            //  || (positionMap.get(market) != null && positionMap.get(market).getType() == PositionType.ENTERING))
            return null;
        }
        double atr = (getShortHigh(market, interval) - bestBid.getPriceCountAsDouble());
        /// volatility) * 100;

        //double atr = atrStop * getVol(market, interval);
        double avg = getAvg(market);
        //double vol = getVol(market);
        log.debug("Short Entry Trigger for interval " + interval + " with atr: " + atr + " atrStop " + atrStop + " average " + avg + " and volatility "
                + volatility + " scaleFactor " + scaleFactor);

        Amount positionUnits = DecimalAmount.ONE;
        Amount positionVolume = DecimalAmount.ZERO;
        Amount workingShortVolume = DecimalAmount.ZERO;
        if (previousBal == null || previousBal.isZero()) {
            previousBal = getBaseLiquidatingValue();
            previousRestBal = previousBal;
        }
        if (getStartingOrignalBal(market) == null && previousBal != null && !previousBal.isZero())
            startingOrignalBal = previousRestBal;

        //TODO reduce the notional balance by 2 x the decrease in orginal balance
        if (market.getMargin() == 1) {
            log.info("Short Entry Prevented for interval " + interval + " as no supported on market: " + market + " at " + context.getTime());
            revertPositionMap(market);

            return null;
        }
        //short entry to can take bid or make ask - increment
        //bids={10.0@606.81;2.0@606.57;12.0@606.45;1.0@605.67;87.0@605.66} asks={-96.0@607.22;-64.0@607.49;-121.0@607.51;-4.0@607.59;-121.0@607.79}
        if (bestBid == null || bestBid.getPriceCount() == 0) {
            log.info("Short Entry Prevented for interval " + interval + " as no ask at: " + context.getTime());
            revertPositionMap(market);

            return null;
        }

        // return buildExitLongOrders();
        //		Collection<Position> positions = portfolioService.getPositions(bestAsk.getMarket().getBase(), bestAsk.getMarket().getExchange());
        //		Iterator<Position> itp = positions.iterator();
        //		while (itp.hasNext()) {
        //			Position position = itp.next();
        //			if (position.isLong())
        //				//return buildExitLongOrders();
        //				return null;
        //		}

        DiscreteAmount atrDiscrete = (new DiscreteAmount((long) (atr), market.getPriceBasis()));
        double contractSize = (market.getContractSize(market));

        DiscreteAmount limitPrice = bestBid.getPrice().decrement(getSlippagePips(bestBid.getPrice()));
        Amount dollarsPerPoint = (limitPrice.increment().minus(limitPrice))
                .times(market.getMultiplier(market, limitPrice, limitPrice.increment()), Remainder.ROUND_EVEN).times(contractSize, Remainder.ROUND_EVEN)
                .times(1 / limitPrice.getBasis(), Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);
        //   : DecimalAmount.ONE;
        //tradedRate.getPrice();

        if (dollarsPerPoint == null || dollarsPerPoint.isZero()) {
            log.info("Long Entry Prevented for interval " + interval + " as dollarsPerPoint is null at: " + context.getTime());
            return null;

        }
        Amount blockValue = dollarsPerPoint.times(0.01, Remainder.ROUND_EVEN).times(limitPrice, Remainder.ROUND_EVEN);

        //= new DiscreteAmount((long) (entryPrice), bestBid.getMarket().getPriceBasis());
        //bestAsk.getPrice();
        DiscreteAmount stopDiscrete = new DiscreteAmount((long) (pricePointsVolatility * atrStop), market.getPriceBasis());
        if (stopDiscrete.isZero()) {
            //   updateStops(OTEBal, bestBid);
            log.info("Short  Entry Prevented for interval " + interval + " as atr is zero at: " + context.getTime());
            revertPositionMap(market);

            return null;
        }
        if (rawSignals && orderService instanceof MockOrderService) {
            if (getNetPosition(market, interval) != null && getNetPosition(market, interval).getShortVolume() != null) {

                Amount openPostionVolume = getNetPosition(market, interval).getShortVolume();
                positionVolume = getNetPosition(market, interval).getOpenVolume();
                log.debug("Short Entry for interval " + interval + " current position volume:" + positionVolume);
                if (openPostionVolume != null && positionVolume.compareTo(openPostionVolume) != 0) {
                    log.error("short postion break Net Position:" + getNetPosition(market, interval) + " Short postion:" + getShortPosition(market, interval));
                    // sum of short opens with long closes
                    positionVolume = getNetPosition(market, interval).getShortVolume();
                    // just sum of short opens
                    openPostionVolume = getShortPosition(market, interval).getOpenVolume();
                }
                // if (openPostionVolume != null && positionVolume.compareTo(openPostionVolume) != 0) {
                //  log.info("short postion break");
                //    positionVolume = getNetPosition(market).getShortVolume();
                //  openPostionVolume = getShortPosition(market).getOpenVolume();
                // }

            } else {
                log.info("Short Entry Prevented for interval " + interval + " already long " + getNetPosition(market, interval) + "at : " + context.getTime());
                revertPositionMap(market);

                return null;
            }

            DiscreteAmount orderDiscrete = new DiscreteAmount(Long.parseLong("1"), market.getVolumeBasis());

            // DiscreteAmount.(BigDecimal.ONE, market.getVolumeBasis());
            DecimalAmount maxAssetPosition = DecimalAmount.of(BigDecimal.ONE);
            //.divide(BigDecimal.valueOf(percentEquityRisked)));

            orderDiscrete = (orderDiscrete.compareTo(maxAssetPosition.minus(getShortPosition(market, interval).getOpenVolume().abs())) >= 0) ? (maxAssetPosition
                    .minus(getShortPosition(market, interval).getOpenVolume().abs())).toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR) : orderDiscrete;

            //DiscreteAmount orderDiscrete = maxAssetPosition.toBasis(bestBid.getMarket().getVolumeBasis(), Remainder.ROUND_EVEN);

            //      logger.info("max position:" + maxAssetPosition.toString() + " max loss:" + maxLossTarget + " notila balance: " + notionalBalance.toString());

            //DiscreteAmount orderDiscrete = unitSize.toBasis(bestBid.getMarket().getVolumeBasis(), Remainder.ROUND_CEILING);
            // DiscreteAmount orderDiscrete = maxAssetPosition.toBasis(bestAsk.getMarket().getVolumeBasis(), Remainder.ROUND_CEILING);

            if (orderDiscrete.isZero() || orderDiscrete.isNegative()) {
                revertPositionMap(market);

                //   updateStops(OTEBal, bestBid);
                return null;
            }

            log.debug(this.getClass().getSimpleName() + ":BuildEnterShortOrders - For interval " + interval
                    + " trading with raw singals and fixed order size of one");
            GeneralOrder askOrder = generalOrderFactory.create(context.getTime(), portfolio, market, orderDiscrete.asBigDecimal().negate(),
                    FillType.TRAILING_STOP_LOSS);

            askOrder.withComment(interval + " Short Entry Order").withStopAmount(stopDiscrete.asBigDecimal())
            // .withPositionEffect(PositionEffect.OPEN);
                    .withPositionEffect(PositionEffect.OPEN).withTimeToLive(timeToLive).withExecutionInstruction(execInst).withOrderGroup(interval);

            askOrder.withLimitPrice(limitPrice.asBigDecimal());
            //  else
            //    longOr

            ArrayList<Order> orderList = new ArrayList<Order>();
            // longOrder.withLimitPrice(null);
            orderList.add(askOrder);
            return orderList;

        }
        //eth
        Amount cashBal = portfolioService.getAvailableBaseBalance(tradedCCY, market.getExchange());
        //if (cashBal)
        Amount totalBal = cashBal;

        // Amount OTEBal = portfolioService.getBaseUnrealisedPnL(market.getTradedCurrency(market));
        //	Amount totalBal = cashBal.plus(portfolioService.getUnrealisedPnL(market.getTradedCurrency()));

        DiscreteAmount targetDiscrete;

        if (targetPrice == 0) {
            targetDiscrete = new DiscreteAmount((long) (atrTarget * atr), market.getPriceBasis());
        }

        else {
            targetDiscrete = (DiscreteAmount) (limitPrice.minus(new DiscreteAmount((long) (targetPrice), market.getPriceBasis()))).abs();
            // targetDiscrete=targetDiscrete.abs();

            // stopDiscrete = new DiscreteAmount((long) (targetDiscrete.getCount() * atrStop), bestBid.getMarket().getPriceBasis());

        }
        //     if (targetDiscrete.isNegative())
        //       return null;
        // DiscreteAmount stopDiscrete = new DiscreteAmount((long) (atrStop * atr), bestAsk.getMarket().getPriceBasis());
        //DiscreteAmount targetDiscrete = new DiscreteAmount((long) (atrTarget * atr), bestAsk.getMarket().getPriceBasis());

        Amount atrUSDDiscrete = atrDiscrete.times(dollarsPerPoint, Remainder.ROUND_CEILING);
        Amount PercentProfit = DecimalAmount.ZERO;
        // so each $ in rela losses reduced notinal by $y
        Amount notionalBaseBalance = getOriginalBaseNotionalBalance(market);
        //.times(scaleFactor, Remainder.ROUND_EVEN);
        // so each $ in rela losses reduced notinal by $y

        if (riskManageNotionalBalance) {
            Amount currentBal = getBaseLiquidatingValue().times(getMarketAllocations().get(market), Remainder.ROUND_EVEN);

            notionalBaseBalance = (currentBal.compareTo(previousBal) < 0) ? previousBal.minus((previousBal.minus(getBaseLiquidatingValue())).times(
                    getLossScalingFactor(), Remainder.ROUND_EVEN)) : currentBal;
            if (previousBal == null)
                previousBal = currentBal;
            else
                previousBal = (currentBal.compareTo(previousBal) > 0) ? currentBal : previousBal;

            //  Amount PercentProfit = DecimalAmount.ONE.minus(getBaseCashBalance().times(startingOrignalBal.invert(), Remainder.ROUND_EVEN));
        } else {
            PercentProfit = DecimalAmount.ONE.minus(getBaseLiquidatingValue().times(previousBal.invert(), Remainder.ROUND_EVEN));

            //PercentProfit = DecimalAmount.ONE.minus(getBaseCashBalance().times(getStartingOrignalBal(market).invert(), Remainder.ROUND_EVEN));
            //Does not consider loss of cureent working orders!
            if (!DecimalAmount.ONE.minus(PercentProfit.times(getLossScalingFactor(), Remainder.ROUND_EVEN)).isPositive())
                return null;
            notionalBaseBalance = (PercentProfit.isPositive()) ? notionalBaseBalance.times(
                    DecimalAmount.ONE.minus(PercentProfit.times(getLossScalingFactor(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN) : notionalBaseBalance;
        }

        if (portfolio.getBaseNotionalBalance().times(getMarketAllocations().get(market), Remainder.ROUND_EVEN).compareTo(notionalBaseBalance) != 0) {
            portfolio.setBaseNotionalBalanceCount(notionalBaseBalance.toBasis(portfolio.getBaseAsset().getBasis(), Remainder.ROUND_EVEN).getCount());
            portfolio.merge();
        }

        if (getStartingBaseNotionalBalance(market) == null)
            setStartingBaseNotionalBalance(notionalBaseBalance);
        //allows for prymidding 
        //originalNotionalBalanceUSD.times(DecimalAmount.ONE.minus(PercentProfit), Remainder.ROUND_EVEN);

        // notionalBalanceUSD = notionalBalanceUSD.times(limitPrice.divide(basePrice.asBigDecimal(), Remainder.ROUND_EVEN), Remainder.ROUND_EVEN);
        notionalBaseBalance = notionalBaseBalance.times(scaleFactor, Remainder.ROUND_EVEN);
        log.debug(this.getClass().getSimpleName() + ":BuildEnterShortOrders for interval " + interval + " at " + context.getTime() + " - notionalBalanceUSD: "
                + portfolio.getBaseNotionalBalance() + " notionalBaseBalance " + notionalBaseBalance + " Cash Balance: " + getBaseLiquidatingValue()
                + " startingOrignalBal: " + getStartingOrignalBal(market) + " originalNotionalBalanceUSD: " + getOriginalBaseNotionalBalance(market)
                + " lossScalingFactor: " + getLossScalingFactor() + " PercentProfit: " + PercentProfit + " scalingFactor " + scaleFactor);

        notionalBalance = notionalBaseBalance.times(baseRate.getPrice().invert(), Remainder.ROUND_EVEN);
        log.debug(this.getClass().getSimpleName() + ":BuildEnterShortOrders - For interval " + interval + " notionalBalance: " + notionalBalance
                + " notionalBalanceUSD: " + notionalBaseBalance + " tradedRate: " + baseRate.getPrice());

        if (!notionalBaseBalance.isPositive() || atrUSDDiscrete.isZero() || stopDiscrete.isZero()) {
            // orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
            log.info("Long  Entry Prevented for interval " + interval + " at " + context.getTime() + " as atrUSDDiscrete: " + atrUSDDiscrete
                    + " notionalBaseBalance:" + notionalBaseBalance);
            revertPositionMap(market);
            //(OTEBal, bestAsk);
            return null;
        }

        //  ((notionalBalanceUSD).times(percentEquityRisked, Remainder.ROUND_EVEN)).dividedBy(
        //          (atrUSDDiscrete.times((limitPrice.invert().times(market.getContractSize(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN)),
        //           Remainder.ROUND_EVEN);

        Amount maxAssetPosition = (((notionalBaseBalance).times(getVolatilityTarget(), Remainder.ROUND_FLOOR)).dividedBy(Math.sqrt(365.0),
                Remainder.ROUND_FLOOR)).dividedBy(blockValue.times(priceVolatility, Remainder.ROUND_FLOOR), Remainder.ROUND_FLOOR);

        //    (MaxUnits.times(unitSize, Remainder.ROUND_FLOOR)).toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR);
        //  percentEquityRisked/maxLossTarget
        Amount unitSize = (maxAssetPosition.dividedBy((maxLossTarget / percentEquityRisked), Remainder.ROUND_FLOOR).compareTo(maxAssetPosition) < 0) ? maxAssetPosition
                : (maxAssetPosition.dividedBy((maxLossTarget / percentEquityRisked), Remainder.ROUND_FLOOR));

        //      Amount unitSize = maxAssetPosition.dividedBy((maxLossTarget / percentEquityRisked), Remainder.ROUND_FLOOR);

        //(atrUSDDiscrete.times(market.getContractSize(), Remainder.ROUND_EVEN)),

        //(atrUSDDiscrete.times((limitPrice.invert().times(market.getContractSize(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN)),
        //  atrUSDDiscrete, 
        //  Remainder.ROUND_EVEN);
        log.debug("Short Entry for interval " + interval + " unit size:" + unitSize + "atr usd discrete: " + atrUSDDiscrete + " dollars per point: "
                + dollarsPerPoint + " national USD balance:" + notionalBaseBalance);

        //  log.info("ATR: " + atrUSDDiscrete.toString() + " unit size: " + unitSize.toString());

        if (unitSize.isZero()) {
            log.info("Short Entry Prevented for interval " + interval + " as unit size is zero at: " + context.getTime());
            revertPositionMap(market);

            //   updateStops(OTEBal, bestBid);
            return null;
        }
        if (getNetPosition(market, interval) != null && getNetPosition(market, interval).getOpenVolume() != null) {

            Amount openPostionVolume = getNetPosition(market, interval).getShortVolume();
            positionVolume = getNetPosition(market, interval).getOpenVolume();
            log.debug("Short Entry for interval " + interval + " current position volume:" + positionVolume);
            if (openPostionVolume != null && positionVolume.compareTo(openPostionVolume) != 0) {
                log.error("short postion break Net Position:" + getNetPosition(market, interval) + " Short postion:" + getShortPosition(market, interval));
                // sum of short opens with long closes
                positionVolume = getNetPosition(market, interval).getOpenVolume();
                // just sum of short opens
                openPostionVolume = getShortPosition(market, interval).getOpenVolume();
            }
            // if (openPostionVolume != null && positionVolume.compareTo(openPostionVolume) != 0) {
            //  log.info("short postion break");
            //    positionVolume = getNetPosition(market).getShortVolume();
            //  openPostionVolume = getShortPosition(market).getOpenVolume();
            // }
            positionUnits = positionVolume.dividedBy(unitSize, Remainder.ROUND_EVEN);
        }
        // if (unitSize.compareTo(maxUnitSize) > 0)
        //    unitSize = maxUnitSize;
        //if (portfolioService.getPositions().isEmpty()
        //	|| (totalBal.minus(portfolioService.getPositions().get(0).getVolume().abs().times(bestAsk.getPrice(), Remainder.ROUND_EVEN))).isPositive()) {

        // if (position != null && position.getAvgStopPrice().invert().isZero())
        //    log.info("issues wiht exit prices");
        Amount exitPrice = (limitPrice.plus(stopDiscrete)); // if (exitPrice.isZero())
        log.debug("short entry for interval " + interval + " order exit price: " + exitPrice + " short entry order limit price: " + limitPrice);
        Amount priceDiff;
        Amount localpriceDiff;
        Amount positionPriceDiff;
        Amount openOrderPriceDiff;

        /*        if (!(market.getTradedCurrency(market).equals(market.getQuote()))) {
                    // we have BTC (BASE)/USD(QUOTE) (traded BTC)->  LTC(BASE)//USD(QUOTE) (traded BTC)

                    priceDiff = (exitPrice.compareTo(limitPrice) > 0) ? limitPrice.invert().minus(exitPrice.invert()) : exitPrice.invert().minus(limitPrice.invert());
                    localpriceDiff = ((limitPrice.plus(stopDiscrete)).compareTo(limitPrice) > 0) ? limitPrice.invert().minus((limitPrice.plus(stopDiscrete)).invert())
                            : (limitPrice.plus(stopDiscrete)).invert().minus(limitPrice.invert());
                    // exitPrice = exitPrice.invert();

                    //assumes eixt price > limit price.

                    positionPriceDiff = (getNetPosition(market).getShortAvgStopPrice().compareTo(getNetPosition(market).getShortAvgPrice()) < 0) ? (getNetPosition(
                            market).getShortAvgStopPrice().invert()).minus(getNetPosition(market).getShortAvgPrice().invert()) : (getNetPosition(market)
                            .getShortAvgPrice().invert()).minus(getNetPosition(market).getShortAvgStopPrice().invert());
                    openOrderPriceDiff = (Order.getOpenAvgStopPrice(shortOpenOrders).compareTo(Order.getOpenAvgPrice(shortOpenOrders)) < 0) ? (Order
                            .getOpenAvgStopPrice(shortOpenOrders).invert()).minus(Order.getOpenAvgPrice(shortOpenOrders).invert()) : (Order
                            .getOpenAvgPrice(shortOpenOrders).invert()).minus(Order.getOpenAvgStopPrice(shortOpenOrders).invert());
                } else {*/
        priceDiff = (exitPrice.compareTo(limitPrice) > 0) ? exitPrice.minus(limitPrice) : limitPrice.minus(exitPrice);
        //        localpriceDiff = ((limitPrice.plus(stopDiscrete)).compareTo(limitPrice) > 0) ? limitPrice.plus(stopDiscrete).minus((limitPrice)) : (limitPrice)
        //              .minus(limitPrice.plus(stopDiscrete));
        // exitPrice = exitPrice.invert();

        //assumes eixt price > limit price.
        //TODO this only considers current market, we should consider the loss across all markets?
        positionPriceDiff = (getNetPosition(market, interval).getShortAvgStopPrice().compareTo(getNetPosition(market, interval).getShortAvgPrice()) < 0) ? (getNetPosition(
                market, interval).getShortAvgPrice()).minus(getNetPosition(market, interval).getShortAvgStopPrice()) : (getNetPosition(market, interval)
                .getShortAvgStopPrice()).minus(getNetPosition(market, interval).getShortAvgPrice());
        openOrderPriceDiff = (Order.getOpenAvgStopPrice(shortOpenOrders).compareTo(Order.getOpenAvgPrice(shortOpenOrders)) < 0) ? (Order
                .getOpenAvgPrice(shortOpenOrders)).minus(Order.getOpenAvgStopPrice(shortOpenOrders)) : (Order.getOpenAvgStopPrice(shortOpenOrders)).minus(Order
                .getOpenAvgPrice(shortOpenOrders));

        //    Amount currentMaxLoss = positionPriceDiff.times(getShortPosition(market).getOpenVolume().abs(), Remainder.ROUND_EVEN).times(market.getContractSize(),
        //  Remainder.ROUND_EVEN);
        // currnet max loss = ( 1/entry price - 1/exit price ) * volume * contract size.
        //  Amount lossBalance = (notionalBalance.times(maxLossTarget, Remainder.ROUND_EVEN)).plus(currentMaxLoss);
        Amount lossAmountPerShare = priceDiff;
        //    .times(market.getMultiplier(market, limitPrice, exitPrice), Remainder.ROUND_EVEN).times(
        //  market.getContractSize(market), Remainder.ROUND_EVEN);

        // = (currentMaxLoss == null || currentMaxLoss.isZero()) ? priceDiff.times(market.getMultiplier(market, limitPrice, exitPrice),
        //       Remainder.ROUND_EVEN).times(market.getContractSize(market), Remainder.ROUND_EVEN) : currentMaxLoss;

        /*if ((portfolio.getBaseAsset().equals(market.getQuote())))

            lossAmountPerShare = (lossAmountPerShare.isZero()) ? DecimalAmount.ONE : priceDiff.times(market.getMultiplier(market, limitPrice, exitPrice), Remainder.ROUND_EVEN)
                    .times(market.getContractSize(market), Remainder.ROUND_EVEN).times(exitPrice, Remainder.ROUND_EVEN);
        else
            lossAmountPerShare = (lossAmountPerShare.isZero()) ? DecimalAmount.ONE : priceDiff.times(market.getMultiplier(market, limitPrice, exitPrice), Remainder.ROUND_EVEN)
                    .times(market.getContractSize(market), Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);
        */

        if (getNetPosition(market, interval) != null && getNetPosition(market, interval).getShortVolume() != null
                && getNetPosition(market, interval).getShortVolume().isNegative())
            log.debug("checkpoint");
        // currnet max loss = ( 1/entry price - 1/exit price ) * volume * contract size.

        Amount currentMaxLossAmount;
        Amount weightedExitPrice = DecimalAmount.ONE;
        Amount weightedEntryPrice = DecimalAmount.ONE;
        // if (!(market.getTradedCurrency(market).equals(market.getQuote())))

        weightedExitPrice = market.getContractSize(market) == 1 ? exitPrice : (Order.getOpenVolume(shortOpenOrders, market).abs().plus(positionVolume).abs())
                .isZero() ? exitPrice : (((getNetPosition(market, interval).getShortAvgStopPrice().times(positionVolume.abs(), Remainder.ROUND_EVEN))
                .plus(Order.getOpenAvgStopPrice(shortOpenOrders).times(Order.getOpenVolume(shortOpenOrders, market).abs(), Remainder.ROUND_EVEN))).dividedBy(
                (Order.getOpenVolume(shortOpenOrders, market).abs().plus(positionVolume.abs())), Remainder.ROUND_EVEN));
        weightedExitPrice = weightedExitPrice.times(1 + slippage, Remainder.ROUND_FLOOR);

        weightedEntryPrice = market.getContractSize(market) == 1 ? limitPrice : (Order.getOpenVolume(shortOpenOrders, market).abs().plus(positionVolume).abs())
                .isZero() ? exitPrice : (((getNetPosition(market, interval).getShortAvgPrice().times(positionVolume.abs(), Remainder.ROUND_EVEN)).plus(Order
                .getOpenAvgPrice(shortOpenOrders).times(Order.getOpenVolume(shortOpenOrders, market).abs(), Remainder.ROUND_EVEN))).dividedBy((Order
                .getOpenVolume(shortOpenOrders, market).abs().plus(positionVolume.abs())), Remainder.ROUND_EVEN));

        Amount currentMaxLoss = (weightedExitPrice.minus(weightedEntryPrice)).abs().times(
                (getShortPosition(market, interval).getOpenVolume().abs().plus(Order.getOpenVolume(shortOpenOrders, market).abs())), Remainder.ROUND_EVEN);
        Amount baseCurrentMaxLossAmount = currentMaxLoss.times(market.getMultiplier(market, weightedEntryPrice, weightedExitPrice), Remainder.ROUND_EVEN)
                .times(contractSize, Remainder.ROUND_EVEN).times(rate.getPrice(), Remainder.ROUND_EVEN);
        //     Amount baseCurrentMaxLossAmount = (currentMaxLoss.times(weightedExitPrice, Remainder.ROUND_CEILING)).times(dollarsPerPoint, Remainder.ROUND_EVEN);
        //
        currentMaxLossAmount = (notionalBaseBalance.times(maxLossTarget, Remainder.ROUND_EVEN)).minus(baseCurrentMaxLossAmount);
        if (lossAmountPerShare.isZero()) {
            log.info("BulidEnterShortOrders: For interval " + interval + " exiting as Loss Amount is Zero." + " priceDiff: " + priceDiff + "multiplier: "
                    + market.getMultiplier(market, limitPrice, exitPrice) + " contractSize: " + market.getContractSize(market) + " currentMaxLoss: "
                    + currentMaxLoss + " exitPrice: " + exitPrice);
            revertPositionMap(market);

            return null;
        }
        /*   if (market.getTradedCurrency(market).equals(market.getQuote()))
               currentMaxLossAmount = (notionalBaseBalance.times(maxLossTarget, Remainder.ROUND_EVEN)).minus(currentMaxLoss.times(rate.getPrice(),
                       Remainder.ROUND_EVEN));
           else
               currentMaxLossAmount = (notionalBaseBalance.times(maxLossTarget, Remainder.ROUND_EVEN)).minus(currentMaxLoss.times(((getNetPosition(market)
                       .getShortAvgStopPrice().plus(Order.getOpenAvgStopPrice(shortOpenOrders))).dividedBy(2, Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN));

        *///  Amount currentMaxLossAmount = (notionalBaseBalance.times(maxLossTarget, Remainder.ROUND_EVEN)).minus(currentMaxLoss.times(getNetPosition(market)
          //        .getShortAvgStopPrice(), Remainder.ROUND_EVEN));

        Amount MaxUnits = ((currentMaxLossAmount).dividedBy(
                ((lossAmountPerShare.times(dollarsPerPoint, Remainder.ROUND_CEILING)).times(unitSize, Remainder.ROUND_FLOOR)), Remainder.ROUND_FLOOR)).toBasis(
                1, Remainder.ROUND_FLOOR);

        //     DiscreteAmount maxAssetPosition = (DiscreteAmount) (MaxUnits.times(unitSize, Remainder.ROUND_FLOOR)).toBasis(market.getVolumeBasis(),
        //           Remainder.ROUND_FLOOR).plus(getShortPosition(market).getOpenVolume().abs());

        //  DiscreteAmount maxAssetPosition = (MaxUnits.times(unitSize, Remainder.ROUND_FLOOR)).toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR);

        //Amount maxAssetPosition = MaxUnits.times(unitSize, Remainder.ROUND_FLOOR);
        // Amount maxAssetPosition = (lossBalance).dividedBy(((priceDiff).times(market.getContractSize(), Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN);

        //   " lossAmount
        if (market.getBase().equals(Currency.LTC))
            log.debug("test");

        log.debug("Short Entry Max for interval " + interval + " Asset Position: " + maxAssetPosition + "notional balance " + notionalBalance
                + " notional base balance: " + notionalBaseBalance + " maxLossTarget : " + maxLossTarget + " lossAmount" + lossAmountPerShare
                + "baseCurrentMaxLossAmount: " + baseCurrentMaxLossAmount

                + "currentMaxLossAmount: " + currentMaxLossAmount + " price diff: " + priceDiff + " multiplier: "
                + market.getMultiplier(market, limitPrice, exitPrice) + " contract size: " + market.getContractSize(market) + "max units:" + MaxUnits);

        // Amount exitPrice = (position == null || position.isFlat()) ? (limitPrice.plus(stopDiscrete)).invert() : position.getAvgStopPrice().invert();
        //Amount maxAssetPosition = ((notionalBalance).times(maxLossTarget, Remainder.ROUND_EVEN)).dividedBy(
        //      (limitPrice.invert().minus(exitPrice)).times(market.getContractSize(), Remainder.ROUND_EVEN), Remainder.ROUND_EVEN);

        if (!maxAssetPosition.minus(positionVolume.abs()).isPositive()) {
            log.info("Long Entry Prevented for interval " + interval + " as  positionVolume :" + positionVolume + " is greater than maxAssetPosition: "
                    + maxAssetPosition);

            revertPositionMap(market);

        }

        Amount orderDiscrete = (unitSize.compareTo(maxAssetPosition.minus(positionVolume.abs())) >= 0) ? maxAssetPosition.minus(positionVolume.abs())
                : unitSize.toBasis(market.getVolumeBasis(), Remainder.ROUND_CEILING);

        //DiscreteAmount orderDiscrete = (unitSize.compareTo(maxAssetPosition) >= 0) ? maxAssetPosition : unitSize.toBasis(market.getVolumeBasis(),
        //      Remainder.ROUND_CEILING);
        log.debug("short entry for interval " + interval + " order order discrete: " + orderDiscrete + " short Position: "
                + getShortPosition(market, interval).getOpenVolume() + "open volume" + Order.getOpenVolume(shortOpenOrders, market) + " max asset position: "
                + maxAssetPosition + "unit size" + unitSize);
        // log.debug("short entry order order discrete: " + BigDecimal.ONE.negate() + " position volume: " + positionVolume + " max asset position : "
        //       + maxAssetPosition + "unit size" + unitSize);
        //  153 (30+30)
        /*        if (maxAssetPosition.compareTo((getShortPosition(market).getOpenVolume().abs().plus(Order.getOpenVolume(shortOpenOrders, market).abs()))) < 0) {
                    log.debug("short entry order  prevneted as current long position volume: " + positionVolume + " and open order volume: "
                            + Order.getOpenVolume(shortOpenOrders, market) + " is greater than max position of " + maxAssetPosition);

                    return null;
                }*/
        // log.debug("short entry order order discrete: " + orderDiscrete + " position volume: " + positionVolume + " max asset position: " + maxAssetPosition);
        //  orderDiscrete = (DiscreteAmount) ((orderDiscrete.compareTo((maxAssetPosition).minus((getShortPosition(market).getOpenVolume().abs().plus(Order
        //        .getOpenVolume(shortOpenOrders, market).abs())))) >= 0) ? (maxAssetPosition).minus(getShortPosition(market).getOpenVolume().abs()
        //      .plus(Order.getOpenVolume(shortOpenOrders, market).abs())) : orderDiscrete);

        // orderDiscrete = (DiscreteAmount) ((orderDiscrete.compareTo(maxAssetPosition.minus(getShortPosition(market).getOpenVolume().abs())) >= 0) ? (maxAssetPosition)
        //       .minus(getShortPosition(market).getOpenVolume().abs()) : orderDiscrete);
        // orderDiscrete.toBasis(market.getVolumeBasis(), Remainder.ROUND_FLOOR);
        //DiscreteAmount orderDiscrete = maxAssetPosition.toBasis(bestBid.getMarket().getVolumeBasis(), Remainder.ROUND_EVEN);

        //      logger.info("max position:" + maxAssetPosition.toString() + " max loss:" + maxLossTarget + " notila balance: " + notionalBalance.toString());

        //DiscreteAmount orderDiscrete = unitSize.toBasis(bestBid.getMarket().getVolumeBasis(), Remainder.ROUND_CEILING);
        // DiscreteAmount orderDiscrete = maxAssetPosition.toBasis(bestAsk.getMarket().getVolumeBasis(), Remainder.ROUND_CEILING);

        if (orderDiscrete.isZero() || orderDiscrete.isNegative()) {

            log.info("Short Entry Prevented for interval " + interval + "  as currnet position: " + getShortPosition(market, interval).getOpenVolume()
                    + " is greater than max position: " + maxAssetPosition.negate() + " at:" + context.getTime() + "with atr:" + atrDiscrete);

            log.trace(getNetPosition(market, interval).getFills().toString());
            revertPositionMap(market);

            //   updateStops(OTEBal, bestBid);
            return null;
        }
        maxPositionUnits = maxAssetPosition.dividedBy(orderDiscrete, Remainder.ROUND_EVEN);
        // if (orderDiscrete.isPositive() && (totalPosition.compareTo(maxAssetPosition.negate()) >= 0)) {

        if (orderDiscrete.isPositive()) {
            GeneralOrder askOrder = generalOrderFactory.create(context.getTime(), portfolio, market, orderDiscrete.asBigDecimal().negate(),
                    FillType.TRAILING_STOP_LOSS);

            askOrder.withComment(interval + " Short Entry Order").withStopAmount(stopDiscrete.asBigDecimal())
                    // .withPositionEffect(PositionEffect.OPEN);
                    .withPositionEffect(PositionEffect.OPEN).withTimeToLive(timeToLive).withTargetAmount(targetDiscrete.asBigDecimal())
                    .withExecutionInstruction(execInst).withOrderGroup(interval);
            Collection<SpecificOrder> pendingOrders = (askOrder.isBid()) ? orderService.getPendingLongOrders() : orderService.getPendingShortOrders();
            Amount workingVolume = orderDiscrete;
            for (SpecificOrder workingOrder : pendingOrders)
                workingVolume = workingVolume.plus(workingOrder.getUnfilledVolume());
            // if I am buying, then I can buy at current best ask and sell at current best bid
            Book lastBook = quotes.getLastBook(market);
            log.info(this.getClass().getSimpleName() + ":getEnterShortOrders - For interval " + interval + " setting limit prices for market " + market
                    + " using lastBook" + lastBook);
            Offer bestOffer = (askOrder.isBid()) ? lastBook.getBestAskByVolume(new DiscreteAmount(DiscreteAmount.roundedCountForBasis(
                    workingVolume.asBigDecimal(), market.getVolumeBasis()), market.getVolumeBasis())) : lastBook.getBestBidByVolume(new DiscreteAmount(
                    DiscreteAmount.roundedCountForBasis(workingVolume.asBigDecimal(), market.getVolumeBasis()), market.getVolumeBasis()));

            // this is short exit, so I am buy, so hitting the ask
            // loop down asks until the total quanity of the order is reached.
            if (askOrder.getExecutionInstruction() != null && askOrder.getExecutionInstruction().equals(ExecutionInstruction.TAKER) && bestOffer != null
                    && bestOffer.getPrice() != null && bestOffer.getPrice().getCount() != 0) {
                // limitPrice = bestOffer.getPrice().decrement(getSlippagePips(limitPrice));
                askOrder.withLimitPrice(limitPrice.increment(getSlippagePips(limitPrice)).asBigDecimal());
                //askOrder.setFillType(FillType.MARKET);

                log.info(this.getClass().getSimpleName() + ":getEnterShortOrders: For interval " + interval + " setting limit price to best offer by volume "
                        + limitPrice + " for order " + askOrder);
            }
            // specificOrder.withLimitPrice(limitPrice);

            //I want to buy. so will buy at highest price
            // longOrder.withLimitPrice(limitPrice.increment(slippage).asBigDecimal());
            // 
            //  else
            //    longOrder.withMarketPrice(limitPrice.asBigDecimal());

            //askOrder.withLimitPrice(limitPrice.decrement(slippage).asBigDecimal());
            // we are selling so we need to 
            //  if (execInst == ExecutionInstruction.MAKER)

            //    askOrder.withMarketPrice(limitPrice.decrement(getSlippagePips(limitPrice)).asBigDecimal());
            //else
            askOrder.withLimitPrice(limitPrice);

            if (limit) {

                DiscreteAmount triggerPrice = (DiscreteAmount) bestBid.getPrice().minus(new DiscreteAmount((long) (atrTrigger * atr), market.getPriceBasis()));

                //  DiscreteAmount triggerPrice = bestBid.getPrice().decrement(triggerBuffer);

                askOrder.withTargetPrice(triggerPrice.asBigDecimal());
            }
            //  Listing costListing = Listing.forPair(market.getBase(), tradedCCY);
            // when I am selling, I am seelling an amount of the basea currency., so the fees will be in the base currency.
            //

            //    adsfasdfas
            // I am selling x ETH, so the fills will be in ETH
            //  asdfasdf

            Listing costListing = Listing.forPair(market.getBase(), tradedCCY);

            Offer costRate = quotes.getImpliedBestAskForListing(costListing);

            Amount totalCost = (FeesUtil.getMargin(askOrder).plus(FeesUtil.getCommission(askOrder))).negate().times(costRate.getPrice(), Remainder.ROUND_EVEN);
            ;
            cashBal = portfolioService.getAvailableBalance(tradedCCY, market.getExchange());
            if ((totalCost.compareTo(cashBal) > 0)) {
                Amount totalCostAmmount = (cashBal.isNegative()) ? totalCost.minus(cashBal) : totalCost;
                cashBal = (portfolioService.getAvailableBalance(portfolio.getBaseAsset(), market.getExchange())).dividedBy(rate.getPrice(),
                        Remainder.ROUND_EVEN);
                if ((totalCost.compareTo(cashBal) > 0)) {
                    Amount qtyScale = cashBal.dividedBy((totalCost.times(1.1, Remainder.ROUND_EVEN)), Remainder.ROUND_EVEN);
                    Amount vol = (qtyScale.isZero()) ? askOrder.getVolume() : askOrder.getVolume().times(qtyScale, Remainder.ROUND_EVEN);
                    askOrder.setVolumeDecimal(vol.asBigDecimal());

                    totalCost = (FeesUtil.getMargin(askOrder).plus(FeesUtil.getCommission(askOrder))).negate();
                    if ((totalCost.compareTo(cashBal) > 0)) {
                        log.info("Short Entry Prevented for interval " + interval + " as total cost " + totalCost + " is greater than cash balance " + cashBal
                                + " at: " + context.getTime());
                        revertPositionMap(market);

                        return null;
                    }

                    //  updateStops(OTEBal, bestBid);

                } else if (cashBal.isPositive() && cashBal.compareTo(totalCost) > 0) {

                    //neeed to transfer the total cost

                    //TODO These transfer should only happen if teh balacnes in the debit currenty is >0.
                    Transaction initialCredit = transactionFactory.create(portfolio, market.getExchange(), tradedCCY, TransactionType.CREDIT, totalCostAmmount,
                            new DiscreteAmount(0, 0.01));
                    context.setPublishTime(initialCredit);

                    initialCredit.persit();

                    context.route(initialCredit);

                    Transaction initialDebit = transactionFactory.create(portfolio, market.getExchange(), portfolio.getBaseAsset(), TransactionType.DEBIT,
                            (totalCostAmmount.times(rate.getPrice(), Remainder.ROUND_EVEN)).negate(), new DiscreteAmount(0, 0.01));
                    context.setPublishTime(initialDebit);

                    initialDebit.persit();

                    context.route(initialDebit);

                    cashBal = portfolioService.getAvailableBalance(tradedCCY, market.getExchange()).plus(totalCostAmmount);

                }
            }

            if ((totalCost.compareTo(cashBal) <= 0)) {
                if (adjustStops)
                    updateShortStops(bestBid);
                if (targetDiscrete != null && adjustStops)
                    updateShortTarget(getNetPosition(market, interval), bestBid, targetDiscrete);
                //orderService.adjustStopLoss(bestBid.getPrice(), stopAdjustment);
                //  updateStops(OTEBal, bestBid);
                ArrayList<Order> orderList = new ArrayList<Order>();
                orderList.add(askOrder);
                if (pairMarket != null) {
                    GeneralOrder pairOrder = generalOrderFactory.create(askOrder);
                    pairOrder.setVolume(askOrder.getVolume().negate());
                    pairOrder.setListing(askOrder.getListing());
                    pairOrder.setMarket(pairMarket);
                    askOrder.addChildOrder(pairOrder);
                    pairOrder.setParentOrder(askOrder);

                    Offer farBestBid = (execInst == (ExecutionInstruction.TAKER)) ? quotes.getLastBidForMarket(pairMarket) : quotes
                            .getLastAskForMarket(pairMarket);

                    // if (execInst == ExecutionInstruction.MAKER)
                    //   pairOrder.withMarketPrice(limitPrice.increment(getSlippagePips(limitPrice)).asBigDecimal());
                    //else
                    pairOrder.withLimitPrice(farBestBid.getPrice().decrement(getSlippagePips(limitPrice)).asBigDecimal());
                    orderList.add(pairOrder);

                }

                //  previousBal = portfolioService.getBaseCashBalance(portfolio.getBaseAsset());
                // log.debug(this.getClass().getSimpleName() + ":BuildEnterShortOrders - setting previousBal to : " + previousBal);

                return orderList;
            } else {

                //   updateStops(OTEBal, bestBid);
                log.info("Short Entry Prevented for interval " + interval + " as total cost " + totalCost + " is greater than cash balance " + cashBal
                        + " at: " + context.getTime());
                revertPositionMap(market);

                return null;
            }
        } else {
            //  updateStops(OTEBal, bestBid);
            log.info("Short Entry Prevented for interval " + interval + " as postions units " + positionUnits + " is greater than max postion units "
                    + maxPositionUnits + " at: " + context.getTime());
            revertPositionMap(market);

            return null;
        }

    }

    //
    //private Offer bestBid;
    //private Offer bestAsk;
    protected static Boolean limit = false;
    private static double lastShortEntryPrice;
    private static double lastShortExitPrice;
    private static double lastLongEntryPrice;
    private static double lastLongExitPrice;

    private static Market cashMarket;
    private final long volumeCount = 0;

    //	private static double interval = 86400;

    //int counter = 0;

    private Amount getStartingBaseNotionalBalance(Market market) {
        if (getMarketAllocations() != null && getMarketAllocations().get(market) != null && startingBaseNotionalBalance != null)
            return startingBaseNotionalBalance.times(getMarketAllocations().get(market), Remainder.ROUND_FLOOR);
        return null;
    }

    private synchronized void setStartingBaseNotionalBalance(Amount startingBaseNotionalBalance) {
        this.startingBaseNotionalBalance = startingBaseNotionalBalance;
    }

    /* private Amount getNotionalBaseBalance(Market market) {
         if (getMarketAllocations() != null && getMarketAllocations().get(market) != null)
             return notionalBaseBalance.times(getMarketAllocations().get(market), Remainder.ROUND_FLOOR);
         return null;

     }

     private synchronized void setNotionalBaseBalance(Amount notionalBaseBalance) {
         this.notionalBaseBalance = notionalBaseBalance;
     }*/

    @Override
    @Nullable
    protected CommonOrderBuilder buildStopOrder(Fill fill) {
        //		if (fill.getOrder().getExitPrice() != null) {
        //			ArrayList<Order> linkedOrders = new ArrayList<Order>();
        //			linkedOrders.add(fill.getOrder());
        //			return order.create(context.getTime(), market, fill.getVolumeCount() * -1, "Stop Order").withStopPrice(fill.getOrder().getExitPrice())
        //					.withLinkedOrders(linkedOrders);
        //		} else {
        return null;
        //		}
    }

    @Override
    @Nullable
    protected CommonOrderBuilder buildExitOrder(Order entryOrder) {
        // TODO Auto-generated method stub
        return null;
    }

}
