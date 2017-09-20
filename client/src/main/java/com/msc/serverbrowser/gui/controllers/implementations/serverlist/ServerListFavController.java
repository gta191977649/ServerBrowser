package com.msc.serverbrowser.gui.controllers.implementations.serverlist;

import java.util.List;

import com.msc.serverbrowser.data.FavouritesController;
import com.msc.serverbrowser.data.entites.SampServer;

/**
 * ViewController for the favourite servers list view.
 *
 * @author Marcel
 */
public class ServerListFavController extends BasicServerListController
{
	@Override
	public void initialize()
	{
		super.initialize();

		servers.addAll(FavouritesController.getFavourites());
	}

	@Override
	protected void displayMenu(final List<SampServer> selectedServers, final double posX, final double posY)
	{
		super.displayMenu(selectedServers, posX, posY);

		addToFavouritesMenuItem.setVisible(false);
		removeFromFavouritesMenuItem.setVisible(true);
	}
}