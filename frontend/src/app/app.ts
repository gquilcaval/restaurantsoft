import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { DialogHostComponent } from './shared/ui/dialog-host.component';
import { ToastHostComponent } from './shared/ui/toast-host.component';

@Component({
  imports: [RouterOutlet, DialogHostComponent, ToastHostComponent],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
}
