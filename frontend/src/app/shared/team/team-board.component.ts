import { CurrencyPipe } from '@angular/common';
import { Component, effect, inject, input, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { MEDALS, TeamRanking } from './team-medals';
import { InfoTipComponent } from '../ui/info-tip.component';

type Period = 'today' | 'week' | 'lastweek';

/** Ranking del equipo con podio animado y medallas de la semana. */
@Component({
  selector: 'app-team-board',
  imports: [CurrencyPipe, InfoTipComponent],
  templateUrl: './team-board.component.html',
  styleUrls: ['./team-board.component.scss', './team-table.scss'],
})
export class TeamBoardComponent {
  private readonly api = inject(ApiService);
  readonly compact = input(false);
  readonly initialPeriod = input<Period>('week');
  readonly period = signal<Period>('week');
  readonly data = signal<TeamRanking | null>(null);
  readonly medals = MEDALS;
  readonly medalKeys = Object.keys(MEDALS);
  private started = false;

  constructor() {
    effect(() => {
      if (!this.started) {
        this.started = true;
        this.period.set(this.initialPeriod());
      }
      const period = this.period();
      this.api.get<TeamRanking>(`/staff/team/leaderboard?period=${period}`).subscribe({
        next: (value) => this.data.set(value),
        error: () => this.data.set(null),
      });
    });
  }

  podium() {
    const list = this.data()?.waiters || [];
    // Orden visual del podio: segundo, primero, tercero.
    return [list[1], list[0], list[2]]
      .map((member, index) => ({ member, place: [2, 1, 3][index] }))
      .filter((item) => item.member);
  }

  winner(medal: string) {
    return this.data()?.waiters.find((member) => member.medals.includes(medal)) || null;
  }

  initials(name: string): string {
    return name
      .split(/\s+/)
      .map((part) => part[0])
      .join('')
      .slice(0, 2)
      .toUpperCase();
  }

  firstName(name: string): string {
    return name.trim().split(/\s+/)[0];
  }
}
